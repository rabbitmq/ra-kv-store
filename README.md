# Raft-based Key/Value Store

This is a tiny key/value store based on [Ra](https://github.com/rabbitmq/ra) developed to be used in certain integration tests.
It is not meant to be a general purpose project.

# Usage

Start the HTTP API:

```
$ make run
```

Use [HTTPie](https://httpie.org/) to play with the KV store. Retrieving a value:
```
$ http GET http://localhost:8080/1
HTTP/1.1 404 Not Found
content-length: 9
content-type: text/plain
date: Wed, 13 Jun 2018 07:12:15 GMT
server: Cowboy

undefined
```

Setting a value:
```
$ http -f PUT http://localhost:8080/1 value=1
HTTP/1.1 204 No Content
date: Wed, 13 Jun 2018 07:12:28 GMT
server: Cowboy

```

Getting the value back:
```
$ http GET http://localhost:8080/1
HTTP/1.1 200 OK
content-length: 1
content-type: text/plain
date: Wed, 13 Jun 2018 07:12:34 GMT
server: Cowboy

1

```

[Comparing-and-swapping](https://en.wikipedia.org/wiki/Compare-and-swap) a value, success case:
```
$ http -f PUT http://localhost:8080/1 value=2 expected=1
HTTP/1.1 204 No Content
date: Wed, 13 Jun 2018 07:13:02 GMT
server: Cowboy

```

[Comparing-and-swapping](https://en.wikipedia.org/wiki/Compare-and-swap) a value, failure case:
```
$ http -f PUT http://localhost:8080/1 value=2 expected=1
HTTP/1.1 409 Conflict
content-length: 1
date: Wed, 13 Jun 2018 07:13:08 GMT
server: Cowboy

2
```

# Jepsen test

See the [readme](jepsen/jepsen.rakvstore/README.md).

# License

RA KV Store is [Apache 2.0 licensed](https://www.apache.org/licenses/LICENSE-2.0.html).

Copyright 2018-2023 Broadcom. All Rights Reserved.
The term Broadcom refers to Broadcom Inc. and/or its subsidiaries.
