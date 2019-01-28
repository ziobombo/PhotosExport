#!/bin/bash

spark-submit \
  --class it.fraguglia.PhotosExport.App \
  --master local[8] \
  --conf 'spark.driver.extraJavaOptions=-Dlog4j.configuration=file:///usr/local/share/java/PhotosExport/log4j.properties'
  --conf 'spark.executor.extraJavaOptions=-Dlog4j.configuration=file:///usr/local/share/java/PhotosExport/log4j.properties'
  /usr/local/share/java/${project.build.finalName}-jar-with-dependencies.jar \
  $@

