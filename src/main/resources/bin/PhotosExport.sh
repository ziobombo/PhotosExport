#!/bin/bash

spark-submit \
  --class it.fraguglia.PhotosExport.App \
  --master local[8] \
  --conf "spark.driver.extraJavaOptions=-Dlog4j.configuration=file:///usr/local/share/java/PhotosExport/log4j.properties -DappSparkLogFile=$appSparkLogFile -DappLogFile=$appLogFile" \
  --conf "spark.executor.extraJavaOptions=-Dlog4j.configuration=file:///usr/local/share/java/PhotosExport/log4j.properties -DappSparkLogFile=$appSparkLogFile -DappLogFile=$appLogFile" \
/usr/local/share/java/PhotosExport-${project.version}-jar-with-dependencies.jar \
  $@