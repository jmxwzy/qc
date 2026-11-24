#!/bin/sh
cd "$(dirname "$0")" || exit 1
echo '打开本机浏览器 http://127.0.0.1:8765'
exec java -jar archive-workbench.jar
