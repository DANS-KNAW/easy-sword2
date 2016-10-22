#!/usr/bin/env bash

ZIP=$1
CHUNK_SIZE=$2

split -b $CHUNK_SIZE $1 $1.

