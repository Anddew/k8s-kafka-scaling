#!/bin/bash

docker build . --no-cache -t abylinovich/streamsets:latest && \
docker push abylinovich/streamsets:latest