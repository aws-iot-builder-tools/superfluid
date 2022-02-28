#!/usr/bin/env bash

START_TIME=$(date +%s)
HEX_TIME=$(printf '%x\n' $START_TIME)
GUID=$(dd if=/dev/random bs=12 count=1 2>/dev/null | od -An -tx1 | tr -d ' \t\n')
TRACE_ID="1-$HEX_TIME-$GUID"

DOC='{"trace_id": "'$TRACE_ID'", "id": "6226467e3f845502", "start_time": 1498082657.37518, "end_time": 1498082695.4042, "name": "test.elasticbeanstalk.com"}'
echo $DOC
aws xray put-trace-segments --trace-segment-documents "$DOC"
