[[ -z "$1" ]] && { echo "user required" >&2; exit 1; }

CUR_DIR="$PWD"
SCRIPT_DIR="$( cd -- "$(dirname "$0")" >/dev/null 2>&1 ; pwd -P )"
cd $SCRIPT_DIR

REPOSITORY_NAME=$(basename "$PWD")
if [[ "${REPOSITORY_NAME,,}" == "docker" ]]; then
   REPOSITORY_NAME=$(cd .. && basename "$PWD")
fi

TAG="$1/$REPOSITORY_NAME:latest"
echo "TAG:$TAG"

docker build -t $TAG .

if [[ "${2,,}" == "push" ]]; then
  docker push $TAG
fi

cd $CUR_DIR