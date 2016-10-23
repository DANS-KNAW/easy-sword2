#!/usr/bin/env bash

USERNAME=user001
PASSWORD=user001

if [ -f vars.sh ]
then
    . vars.sh
fi

curl -u $USERNAME:$PASSWORD $1
