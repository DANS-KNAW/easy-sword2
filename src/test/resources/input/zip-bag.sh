#!/usr/bin/env bash

BAG_PARENT_DIR=`dirname $1`
BAG_NAME=`basename $1`

pushd $BAG_PARENT_DIR
zip -r $BAG_NAME.zip $BAG_NAME
popd
mv $BAG_PARENT_DIR/$BAG_NAME.zip .


