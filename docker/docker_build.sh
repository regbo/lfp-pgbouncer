set -e

# Default values of arguments
repositoryUser=""
mavenBuild=""
PUSH="false"
OTHER_ARGUMENTS=()

echo $@

# Loop through arguments and process them
for arg in "$@"
do
    case $arg in
        -P|--push)
        PUSH="true"
        shift
        ;;
        -R=*|--repositoryUser=*)
        repositoryUser="${arg#*=}"
        shift 
        ;;
        -M=*|--mavenBuild=*)
        mavenBuild="${arg#*=}"
        shift
        ;;
        *)
        OTHER_ARGUMENTS+=("$1")
        shift
        ;;
    esac
done

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

[[ -z "$repositoryUser" ]] && { echo "repositoryUser required" >&2; exit 1; }

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

if [[ ! -z "$mavenBuild" ]]; then
    if [[ "${SCRIPT_DIR_NAME,,}" == "docker" ]]; then
        cd ..
    fi
	
	FILES_SHA1_VALUE=$(files_hash_current)
	FILES_SHA1_VALUE_UPDATED=$(files_hash_update)
	if [[ "$FILES_SHA1_VALUE" != "$FILES_SHA1_VALUE_UPDATED" ]]; then
		MAVEN_COMMAND="mvn package -T 2.0C"
		if command -v cmd.exe &> /dev/null
		then
			set +e
			cmd.exe /c "mvn -version"
			set -e
			if [[ $? -eq 0 ]]; then
				MAVEN_COMMAND="cmd.exe /c \"$MAVEN_COMMAND\""
			fi
		fi
		bash -c "$MAVEN_COMMAND"
		export APP_JAR=$(find "./$mavenBuild/target" -type f -name \*.jar | xargs ls -1S | head -n 1)
		echo "build complete:${APP_JAR}"
		files_hash_update
	fi
    cd $SCRIPT_DIR
fi



TAG="$repositoryUser/$REPOSITORY_NAME:latest"
echo "TAG:$TAG"

docker build -t $TAG -f "${SCRIPT_DIR}/Dockerfile" $DOCKER_BUILD_DIR

if [[ "${PUSH,,}" == "push" ]]; then
  docker push $TAG
fi

cd $CUR_DIR