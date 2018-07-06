#!/usr/bin/env bash
#  /etc/init.d/easy-sword2

# chkconfig: 2345 92 58

# Provides:          easy-sword2
# Short-Description: Starts the easy-sword2 service
# Description:       This file is used to start the daemon
#                    and should be placed in /etc/init.d

NAME="easy-sword2"
NICENESS=0
EXEC="nice -n $NICENESS /usr/bin/jsvc"
APPHOME="/opt/dans.knaw.nl/$NAME"
JAVA_HOME="/usr/lib/jvm/jre"
CLASSPATH="$APPHOME/bin/$NAME.jar"
CLASS="nl.knaw.dans.easy.sword2.ServiceStarter"
ARGS=""
USER="$NAME"
PID="/var/run/$NAME.pid"
OUTFILE="/var/opt/dans.knaw.nl/log/$NAME/$NAME.out"
ERRFILE="/var/opt/dans.knaw.nl/log/$NAME/$NAME.err"
WAIT_TIME=60

jsvc_exec()
{
    cd $APPHOME
    LOGBACK_CFG=/etc/opt/dans.knaw.nl/$NAME/logback-service.xml
    if [ ! -f $LOGBACK_CFG ]; then
        LOGBACK_CFG=$APPHOME/cfg/logback-service.xml
    fi

    LC_ALL=en_US.UTF-8 \
    $EXEC -home $JAVA_HOME -cp $CLASSPATH -user $USER -outfile $OUTFILE -errfile $ERRFILE -pidfile $PID -wait $WAIT_TIME \
          -Dapp.home=$APPHOME \
          -Dlogback.configurationFile=$LOGBACK_CFG $1 $CLASS $ARGS
}

start_jsvc_exec()
{
    jsvc_exec
    if [[ $? == 0 ]]; then # start is successful
        echo "$NAME has started."
    else
        echo "$NAME did not start successfully (exit code: $?)."
    fi
}

stop_jsvc_exec()
{
    jsvc_exec "-stop"
    if [[ $? == 0 ]]; then # stop is successful
        echo "$NAME has stopped."
    else
        echo "$NAME did not stop successfully (exit code: $?)".
    fi
}

restart_jsvc_exec()
{
    echo "Restarting $NAME ..."
    jsvc_exec "-stop"
    if [[ $? == 0 ]]; then # stop is successful
        echo "$NAME has stopped, starting again ..."
        jsvc_exec
        if [[ $? == 0 ]]; then # start is successful
            echo "$NAME has restarted."
        else
            echo "$NAME did not start successfully (exit code: $?)."
        fi
    else
        echo "$NAME did not stop successfully (exit code: $?)."
    fi
}

case "$1" in
    start)
        if [ -f "$PID" ]; then # service is running
            echo "$NAME is already running, no action taken."
            exit 1
        else
            echo "Starting $NAME ..."
            start_jsvc_exec
        fi
    ;;
    stop)
        if [ -f "$PID" ]; then # service is running
            echo "Stopping $NAME ..."
            stop_jsvc_exec
        else
            echo "$NAME is not running, no action taken."
            exit 1
        fi
    ;;
    restart)
        if [ -f "$PID" ]; then # service is running
            restart_jsvc_exec
        else
            echo "$NAME is not running, just starting ..."
            start_jsvc_exec
        fi
    ;;
    status)
        if [ -f "$PID" ]; then # if service is running
            echo "$NAME (pid `cat $PID`) is running."
        else
            echo "$NAME is stopped."
        fi
    ;;
    *)
        echo "Usage: sudo service $NAME {start|stop|restart|status}" >&2
        exit 3
    ;;
esac
