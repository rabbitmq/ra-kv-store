#!/bin/bash

set -ex

set +x
# create SSH key that will be used to connect to the Jepsen VMs
ssh-keygen -t rsa -m pem -f jepsen-bot -C jepsen-bot -N ''

mkdir -p ~/.aws
echo "$AWS_CONFIG" > ~/.aws/config 
echo "$AWS_CREDENTIALS" > ~/.aws/credentials 
set -x

# destroy existing resources in they already exist
set +e
aws ec2 terminate-instances --no-cli-pager --instance-ids $(aws ec2 describe-instances --query 'Reservations[].Instances[].InstanceId' --filters "Name=tag:Name,Values=JepsenRaKvStore" --output text)
aws ec2 delete-key-pair --no-cli-pager --key-name jepsen-ra-kv-store-key
set -e

# copy Terraform configuration file in current directory
cp ./ci/ra-jepsen-aws.tf .

# initialize Terraform (get plugins and so)
terraform init

# spin up the VMs
terraform apply -auto-approve

mkdir terraform-state

# save Terraform state and configuration to clean up even if the task fails
cp jepsen-bot terraform-state
cp jepsen-bot.pub terraform-state
cp -r .terraform terraform-state
cp terraform.tfstate terraform-state
cp ra-jepsen-aws.tf terraform-state

# get the Jepsen controller IP
CONTROLLER_IP=$(terraform output -raw controller_ip)
JEPSEN_USER="admin"
# install dependencies and compile the RA KV store on the Jepsen controller
ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP 'bash -s' < ci/provision-jepsen-controller.sh
# makes sure the Jepsen test command line works
ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "source ~/.profile ; cd ~/ra_kv_store/jepsen/jepsen.rakvstore/ ; lein run test --help"

# add the worker hostnames to /etc/hosts
WORKERS_HOSTS_ENTRIES=$(terraform output -raw workers_hosts_entries)
ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "echo '$WORKERS_HOSTS_ENTRIES' | sudo tee --append /etc/hosts"

# copy the RA KV store distribution on all the Jepsen workers
WORKERS=( $(terraform output -raw workers_hostname) )
for worker in "${WORKERS[@]}"
do
  ssh -o StrictHostKeyChecking=no -i jepsen-bot \
    $JEPSEN_USER@$CONTROLLER_IP \
    "scp -o StrictHostKeyChecking=no -i ~/jepsen-bot ~/ra_kv_store/jepsen/jepsen.rakvstore/ra_kv_store_release-1.tar.gz $JEPSEN_USER@$worker:/tmp/ra_kv_store_release-1.tar.gz"
done

# create directory for broker /var directory archiving on all the Jepsen workers
WORKERS_IP=( $(terraform output -raw workers_ip) )
for worker_ip in "${WORKERS_IP[@]}"
do
  ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$worker_ip "mkdir /tmp/ra-kv-store-var"
done

# miscellaneous configuration on all the Jepsen workers
for worker_ip in "${WORKERS_IP[@]}"
do
  ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$worker_ip "sudo apt-get update"
  ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$worker_ip "echo '$WORKERS_HOSTS_ENTRIES' | sudo tee --append /etc/hosts"
done

# build up some fixed parameters for the Jepsen tests
NODES=""
for worker in "${WORKERS[@]}"
do
  NODES="$NODES --node $worker"
done

SOURCE_AND_CD="source ~/.profile ; cd ~/ra_kv_store/jepsen/jepsen.rakvstore/"
CREDENTIALS="--username $JEPSEN_USER --ssh-private-key ~/jepsen-bot"

JEPSEN_TESTS_PARAMETERS=(
  "--workload set --nemesis random-partition-halves --time-limit 180 --concurrency 10 --rate 50 --erlang-net-ticktime 7 --disruption-duration 25"
  "--workload set --nemesis partition-halves --time-limit 180 --concurrency 10 --rate 50 --erlang-net-ticktime 7 --disruption-duration 25"
  "--workload set --nemesis partition-majorities-ring --time-limit 180 --concurrency 10 --rate 50 --erlang-net-ticktime 7 --disruption-duration 25"
  "--workload set --nemesis partition-random-node --time-limit 180 --concurrency 10 --rate 50 --erlang-net-ticktime 7 --disruption-duration 25"
  "--workload set --nemesis kill-erlang-process --time-limit 120 --concurrency 10 --rate 50 --time-before-disruption 3 --disruption-duration 3 --release-cursor-every 10 --random-nodes 2"
  "--workload set --nemesis kill-erlang-vm --time-limit 120 --concurrency 10 --rate 50 --time-before-disruption 10 --disruption-duration 3 --release-cursor-every 10 --random-nodes 2"
  "--workload register --nemesis random-partition-halves --time-limit 180 --concurrency 10 --rate 40 --ops-per-key 25 --time-before-disruption 15 --disruption-duration 25 --erlang-net-ticktime 10"
  "--workload register --nemesis kill-erlang-vm --time-limit 120 --concurrency 10 --rate 30 --ops-per-key 10 --time-before-disruption 5 --disruption-duration 20 --erlang-net-ticktime 6 --release-cursor-every 10 --wal-max-size-bytes 524288 --random-nodes 2"
  "--workload register --nemesis kill-erlang-process --time-limit 180 --concurrency 10 --rate 40 --ops-per-key 25 --time-before-disruption 3 --disruption-duration 3 --erlang-net-ticktime 10 --release-cursor-every 10 --wal-max-size-bytes 524288 --random-nodes 2"
  "--workload register --nemesis combined --time-limit 120 --concurrency 10 --rate 40 --ops-per-key 10 --time-before-disruption 15 --disruption-duration 20 --erlang-net-ticktime 6 --release-cursor-every 10 --wal-max-size-bytes 524288 --random-nodes 2"
  "--workload set --nemesis combined --time-limit 240 --concurrency 20 --rate 40 --time-before-disruption 25 --disruption-duration 30 --erlang-net-ticktime 10 --release-cursor-every 10 --wal-max-size-bytes 524288 --random-nodes 1"
)

TESTS_COUNT=${#JEPSEN_TESTS_PARAMETERS[@]}
TEST_INDEX=1

failure=false

set +e

for jepsen_test_parameter in "${JEPSEN_TESTS_PARAMETERS[@]}"
do
  n=1
  until [ $n -ge 5 ]
  do
    echo "Running Jepsen test $TEST_INDEX / $TESTS_COUNT, attempt $n ($(date))"
    ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "$SOURCE_AND_CD ; lein run test $NODES $CREDENTIALS $jepsen_test_parameter --erlang-distribution-url file:///tmp/ra_kv_store_release-1.tar.gz" >/dev/null
    run_exit_code=$?
	for worker_ip in "${WORKERS_IP[@]}"
	do
		SAVE_VAR_DIRECTORY="/tmp/ra-kv-store-var/test-$TEST_INDEX-attempt-$n"
		ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$worker_ip \
			"mkdir $SAVE_VAR_DIRECTORY ; cp -R /tmp/ra_kv_store/* $SAVE_VAR_DIRECTORY"
	done
    ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "$SOURCE_AND_CD ; head -n 50 store/current/jepsen.log"
    ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "$SOURCE_AND_CD ; tail -n 50 store/current/jepsen.log"

	if [ $run_exit_code -eq 0 ]; then
	    # run returned 0, but checking the logs for some corner cases
	    ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "$SOURCE_AND_CD ; grep -q 'Set was never read' ./store/latest/jepsen.log"
	    if [ $? -eq 0 ]; then
	        # Could not read the final data structure, see if we can retry
			if [ $n -ge 4 ]; then
		        # It was the last attempt
		        echo "Test $TEST_INDEX / $TESTS_COUNT failed several times with unexpected errors or inappropriate results, moving on"
		        # We mark this run as failed
		        failure=true
		        break
		    else
		        echo "Final data structure could not be read, retrying"
		    fi
		else
		    # run succeeded, moving on
		    break
		fi
	else
		echo "Test has failed, checking whether it is an unexpected error or not"
		ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "$SOURCE_AND_CD ; grep -q 'Analysis invalid' ./store/latest/jepsen.log"
		if [ $? -eq 0 ]; then
			echo "Test $TEST_INDEX / $TESTS_COUNT failed, moving on"
			failure=true
			break
		else
		    if [ $n -ge 4 ]; then
		        # It was the last attempt
		        echo "Test $TEST_INDEX / $TESTS_COUNT failed several times with unexpected errors, moving on"
		        # We mark this run as failed
		        failure=true
		        break
		    fi
		    echo "Unexpected error, retrying"
		fi
	fi
	n=$[$n+1]
  done
  ((TEST_INDEX++))
done

the_date=$(date '+%Y%m%d-%H%M%S')
archive_name="ra-jepsen-$the_date-jepsen-logs"
archive_file="$archive_name.tar.gz"
ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "$SOURCE_AND_CD ; tar -zcf - store --transform='s/^store/${archive_name}/'" > $archive_file
aws s3 cp $archive_file s3://jepsen-tests-logs/ --quiet

WORKER_INDEX=0
for worker_ip in "${WORKERS_IP[@]}"
do
	var_archive_name="ra-jepsen-$the_date-var-node-$WORKER_INDEX"
	var_archive_file="$var_archive_name.tar.gz"
	ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$worker_ip \
		"cd /tmp ; tar -zcf - ra-kv-store-var --transform='s/^ra-kv-store-var/${var_archive_name}/'" > $var_archive_file
  aws s3 cp $var_archive_file s3://jepsen-tests-logs/ --quiet
	((WORKER_INDEX++))
done

echo "Download logs: aws s3 cp s3://jepsen-tests-logs/ . --recursive --exclude '*' --include 'ra-jepsen-$the_date*'"

if [ "$failure" = true ]; then
  exit 1
else
  exit 0
fi
