#!/usr/bin/env zsh

# Generating X-Ray record in shell based on docs at: https://docs.aws.amazon.com/xray/latest/devguide/xray-api-sendingdata.html#xray-api-segments
# Guidance from: https://unix.stackexchange.com/a/191209
#   Requires zsh!
HEX_TIME=$(([##16]$(date +%s)))

# Guidance from: https://stackoverflow.com/a/34329057/796579
GUID=$(hexdump -n 12 -e '3/4 "%08X" 1 "\n"' /dev/urandom)
TRACE_ID="1-$HEX_TIME-$GUID"
echo $TRACE_ID
DOC='{"trace_id": "'$TRACE_ID'", "id": "6226467e3f845502", "start_time": 1498082657.37518, "end_time": 1498082695.4042, "name": "test.elasticbeanstalk.com"}'
echo $DOC
aws xray put-trace-segments --trace-segment-documents "$DOC"
