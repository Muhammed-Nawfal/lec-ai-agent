#!/usr/bin/env bash
# Builds the project and runs it.
#
# Usage:
#   ./run.sh                 -> build + run PipelineRunner (all fetched tasks)
#   ./run.sh 10               -> build + run PipelineRunner, capped to 10 tasks
#   ./run.sh stress           -> build + run StressTestRunner (default 50 runs)
#   ./run.sh stress 20        -> build + run StressTestRunner, 20 runs
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
    echo "ERROR: .env not found in $(pwd)." >&2
    echo "Create one with: SUPABASE_DB_HOST, SUPABASE_DB_PORT, SUPABASE_DB_NAME, SUPABASE_DB_USER, SUPABASE_DB_PASSWORD, GOOGLE_API_KEY, URLHAUS_AUTH_KEY" >&2
    exit 1
fi

echo "Building (mvn package)..."
mvn -q package -DskipTests

JAR="target/lec-ai-agent.jar"

if [ "${1:-}" = "stress" ]; then
    shift
    echo "Running StressTestRunner..."
    java -cp "$JAR" com.lecai.agent.exec.StressTestRunner "$@"
else
    echo "Running PipelineRunner..."
    java -cp "$JAR" com.lecai.agent.exec.PipelineRunner "$@"
fi
