#!/bin/bash

set -e

#ARG DEFAULTS
REPOSITORY_USERNAME=""
MAVEN_DIR=""
SKIP_MAVEN_BUILD_ON_NO_CHANGE=0
PUSH_DOCKER_IMAGE=0
OTHER_ARGUMENTS=()

#ARG PARSE
for arg in "$@"
do
    case $arg in
        -P|--push)
        PUSH_DOCKER_IMAGE=1
        shift
        ;;
        -S|--skipMavenBuildOnNoChange)
        SKIP_MAVEN_BUILD_ON_NO_CHANGE=1
        shift
        ;;
        -R=*|--repositoryUsername=*)
        REPOSITORY_USERNAME="${arg#*=}"
        shift 
        ;;
        -M=*|--mavenDir=*)
        MAVEN_DIR="${arg#*=}"
        shift
        ;;
        *)
        OTHER_ARGUMENTS+=("$1")
        shift
        ;;
    esac
done
[[ -z "$REPOSITORY_USERNAME" ]] && { echo "--repositoryUsername required" >&2; exit 1; }

#UTILS

FILES_HASH_STORE=".files-hash"

function files_hash_current(){
	if [[ -f "$FILES_HASH_STORE" ]]; then
		echo $(cat $FILES_HASH_STORE)
	else
		echo ""
	fi
}

function files_hash_update(){
	find $PWD -type f -not -path '*/.*' -print0 | sort -z | xargs -0 sha1sum | sha1sum > $FILES_HASH_STORE
	files_hash_current
}

function is_success(){
	$@ || exit_status=$?
	return $exit_status
}

#ALIAS

if is_success cmd.exe /c "mvn -version"
then
	alias mvn="cmd.exe /c \"mvn $@\""
fi

CUR_DIR="$PWD"
SCRIPT_DIR="$( cd -- "$(dirname "$0")" >/dev/null 2>&1 ; pwd -P )"
cd $SCRIPT_DIR
SCRIPT_DIR_NAME=$(basename "$PWD")

REPOSITORY_NAME=$SCRIPT_DIR_NAME
DOCKER_BUILD_DIR=""
if [[ "${REPOSITORY_NAME,,}" == "docker" ]]; then
   REPOSITORY_NAME=$(cd .. && basename "$PWD")
   DOCKER_BUILD_DIR="../"
fi

if [[ ! -z "$MAVEN_DIR" ]]; then
    if [[ "${SCRIPT_DIR_NAME,,}" == "docker" ]]; then
        cd ..
    fi
	FILES_SHA1_VALUE=$(files_hash_current)
	FILES_SHA1_VALUE_UPDATED=$(files_hash_update)
	if [[ $SKIP_MAVEN_BUILD_ON_NO_CHANGE == 0 ]] || [[ "$FILES_SHA1_VALUE" != "$FILES_SHA1_VALUE_UPDATED" ]]; then
		mvn package -T 2.0C
		export APP_JAR=$(find "./$MAVEN_DIR/target" -type f -name \*.jar | xargs ls -1S | head -n 1)
		echo "maven build complete:$APP_JAR"
		files_hash_update
	fi
    cd $SCRIPT_DIR
fi

TAG="$REPOSITORY_USERNAME/$REPOSITORY_NAME:latest"
echo "TAG:$TAG"

docker build -t $TAG -f "${SCRIPT_DIR}/Dockerfile" $DOCKER_BUILD_DIR

if [[ $PUSH_DOCKER_IMAGE != 0 ]]; then
  docker push $TAG
fi

cd $CUR_DIR