#!/usr/bin/env bash

/wait

java -cp "/opt/brvs/*" "iroha.validation.Application" "config/context/spring-context.xml"
