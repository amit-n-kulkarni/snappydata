#!/usr/bin/env bash
source PerfRun.conf

echo "*****************Stop locator, server, lead***********************"
sh $SnappyData/build-artifacts/scala-2.10/snappy/sbin/snappy-stop-all.sh

rm -rf $SnappyData/build-artifacts/scala-2.10/snappy/work/*

# Below script is used only in cluster mode
echo "*****************kill java on lead**********************"
ssh $leads killall -9 java
echo "*****************kill java on locator**********************"
ssh $locator killall -9 java
echo "*****************kill java on server***********************"

for element in "${servers[@]}";
  do
	ssh $element killall -9 java
  done

