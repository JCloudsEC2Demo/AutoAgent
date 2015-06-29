#!/bin/bash

AGENT_CTRL_IP=176.34.4.181
AGENT_CTRL_PORT=8080
AGENT_IMG_REPO=ngrinder/agent
AGENT_IMG_TAG=3.3
AGENT_WORK_MODE="TURNON"

####################################################################################
START_TIME=`date +%T`
DATE=`date +%Y-%m-%d-%H%M-%S --date=$START_TIME`
PID=$$
####################################################################################

function SCRIPT_START()
{
    LOG_PRINT "PID             :  $PID"
    LOG_PRINT `echo`
}

function LOG_PRINT()
{
    echo "[`date "+%D %T"`][$AGENT_WORK_MODE]  $1" >> /tmp/AutoAgentProvision.log
}

function check_install_docker()
{
    sudo docker 1>>/dev/null 2>&1
    if [[ $? == 0 ]]; then
       LOG_PRINT "Docker is existing already"
    else
       sudo wget -qO- https://get.docker.com/ | sh >> /tmp/AutoAgentProvision.log 2>&1
       #sudo yum install -y docker-io >> /tmp/AutoAgentProvision.log 2>&1
       if [[ $? != 0 ]];then
           LOG_PRINT "Install docker failed"
           exit 1
       fi
       LOG_PRINT "Docker is installed successfully"
    fi
    sleep 2
    sudo service docker start >> /tmp/AutoAgentProvision.log 2>&1
    if [[ $? == 0 ]];then
       LOG_PRINT "First to start docker daemon service successfully.."
    else
       LOG_PRINT "First to start docker daemon service failed..."
       sudo service docker start >> /tmp/AutoAgentProvision.log 2>&1
       if [[ $? == 0 ]];then
           LOG_PRINT "Second to start docker daemon service successfully.."
       else
           LOG_PRINT "Second to start docker daemon service failed..."
       fi
    fi   
}

function check_pull_agent_image()
{
    IS_FOUND="NO"
    finds=`sudo docker images | awk '{print $1":"$2}'`
    for item in $finds
    do
        if [[ $item == $AGENT_IMG_REPO:$AGENT_IMG_TAG ]]; then
            IS_FOUND="YES"
            break
        fi
    done
    if [[ $IS_FOUND != "YES" ]];then
       LOG_PRINT "Begin to pull ngrinder agent [$AGENT_IMG_REPO:$AGENT_IMG_TAG]..."
       sudo docker pull $AGENT_IMG_REPO:$AGENT_IMG_TAG >> /tmp/AutoAgentProvision.log 2>&1
       if [[ $? != 0 ]]; then
          LOG_PRINT ">>>>>----->>>>> docker pull $AGENT_IMG_REPO:$AGENT_IMG_TAG failed..."
          exit 2
       else
          LOG_PRINT "=============== docker pull $AGENT_IMG_REPO:$AGENT_IMG_TAG successfully..."
       fi
    else
       LOG_PRINT "Agent image [$AGENT_IMG_REPO:$AGENT_IMG_TAG] is existing."
    fi
}

function check_start_agent_container()
{
    AGENT_RUNNING="NO"
    agents=`sudo docker ps | awk '{print $2}'`
    for item in $agents
    do
        if [[ $item == $AGENT_IMG_REPO:$AGENT_IMG_TAG ]]; then
            AGENT_RUNNING="YES"
            break
        fi
    done
    if [[ $AGENT_RUNNING != "YES" ]];then
        sudo docker run -d -e "CONTROLLER_ADDR=$AGENT_CTRL_IP:$AGENT_CTRL_PORT" $AGENT_IMG_REPO:$AGENT_IMG_TAG >>/tmp/AutoAgentProvision.log 2>&1
        if [[ $? != 0 ]]; then
            LOG_PRINT "Start docker container [$AGENT_CTRL_IP:$AGENT_CTRL_PORT]- [$AGENT_IMG_REPO:$AGENT_IMG_TAG] failed..."
            exit 3
        else
            LOG_PRINT "Start docker container [$AGENT_CTRL_IP:$AGENT_CTRL_PORT]- [$AGENT_IMG_REPO:$AGENT_IMG_TAG] successfully..."
	fi        
    else
        LOG_PRINT "Docker container [$AGENT_CTRL_IP:$AGENT_CTRL_PORT]- [$AGENT_IMG_REPO:$AGENT_IMG_TAG] is running..."
    fi
}

function check_stop_remove_agent_container()
{
    agents=`sudo docker ps | awk '{print $1}'`
    for item in $agents
    do
        if [[ X$item != "CONTAINER ID" ]];then
            sudo docker stop -f $item >> /tmp/AutoAgentProvision.log 2>&1
            sleep 1
            sudo docker rm -f $item >> /tmp/AutoAgentProvision.log 2>&1
            sleep 1
        fi
    done
}

SCRIPT_START
case $AGENT_WORK_MODE in
    "RUN")
        check_install_docker
        check_pull_agent_image
        check_start_agent_container
        ;;
    "TURNON")
        check_install_docker
        check_stop_remove_agent_container
        check_start_agent_container
        ;;
    "TURNOFF")
        check_stop_remove_agent_container
        ;;
     *)
        LOG_PRINT "Error, not supported operation: $AGENT_WORK_MODE"
        ;;
esac
