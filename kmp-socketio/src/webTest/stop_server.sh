#!/bin/sh

PORT=3000
PID=$(lsof -i :$PORT -t)

if [ -z "$PID" ]; then
    echo "port $PORT no listening"
else
    echo "killing $PID..."
    kill -9 $PID
    if [ $? -eq 0 ]; then
        echo "kill $PID success"
    else
        echo "kill $PID fail"
    fi
fi
