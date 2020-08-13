#!/bin/bash

# Gets the jar file path arguments
jarFile=$1

# runs the application
# Redis host name comes from an environment variable
java -jar $jarFile $REDIS_HOST