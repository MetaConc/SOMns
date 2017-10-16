#!/bin/bash
# quit on first error
set -e

declare -a Savina=(
  "utils"  
  "createDeleteFile"
  "createDeleteDirectory"
  "readwrite"
  "paths"
  "move"
) 

## Determine absolute path of script
pushd `dirname $0` > /dev/null
SCRIPT_PATH=`pwd`
popd > /dev/null

SOM_DIR=$SCRIPT_PATH/../..

for args in "${Savina[@]}"   
do  
  echo "Testing $args" 
  $SOM_DIR/som -G -dnu -JXmx1500m -vmd $SOM_DIR/core-lib/TestSuite/FileTests.ns $SCRIPT_PATH $args 
  echo "" 
  echo "========================================================" 
  echo "" 
done 

