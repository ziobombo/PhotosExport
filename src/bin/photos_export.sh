#!/bin/bash

spark-submit \
  --class it.fraguglia.PhotosExport.App \
  --master local[8] \
  /usr/local/share/java/${project.build.finalName}-jar-with-dependencies.jar \
  $@

