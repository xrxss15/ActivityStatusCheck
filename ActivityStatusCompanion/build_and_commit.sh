#!/bin/bash

echo "==========================================="
echo "Build and Commit Script"
echo "==========================================="

echo ""
echo "Building Android project..."
./gradlew assembleDebug

if [ $? -ne 0 ]; then
    echo ""
    echo "[ERROR] Build failed!"
    echo "Check the build output above for errors."
    exit 1
fi

echo ""
echo "[SUCCESS] Build completed successfully!"
echo ""

read -p "Enter commit message: " commit_message

if [ -z "$commit_message" ]; then
    echo "[ERROR] Commit message cannot be empty!"
    exit 1
fi

echo ""
echo "Committing changes..."
git add -A && git commit -m "$commit_message" && git push

if [ $? -ne 0 ]; then
    echo ""
    echo "[ERROR] Git operations failed!"
    exit 1
fi

echo ""
echo "[SUCCESS] Changes committed and pushed successfully!"
echo "Commit message: $commit_message"
