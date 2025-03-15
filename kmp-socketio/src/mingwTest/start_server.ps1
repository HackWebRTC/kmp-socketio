powershell -Command { Get-NetTCPConnection -LocalPort 3000 | Select-Object -ExpandProperty OwningProcess | ForEach-Object { Stop-Process -Id $_ -Force } }

node src/jvmTest/resources/socket-server.js /
