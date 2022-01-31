#!/bin/bash

# This script will use OWASP's Depency Check that will check all java based archives (.ear, .war, .jar, etc) in a directory and create an HTML report.
# Internet connection is required as the version will be checked and updated, if necessary.  In addition, the depency check tool downloads CVE's to its databasse.
#
# Requires: bash, sed, curl, unzip, java
#
# Usage: security-check.sh path_to_files
#
# Brian S Paskin
# 1.0.0 (31/01/2022)

# check number of params passed
if [ "$#" -ne 1 ]; then
    echo "usage: security-check.sh path_to_files"
    exit 1
fi

scan_dir=$1

# check if Dependency Check is already available and check version
if [[ -f dependency-check/bin/dependency-check.sh ]]; then
    version="$(dependency-check/bin/dependency-check.sh --version | sed 's/^.*[^0-9]\([0-9]*\.[0-9]*\.[0-9]*\).*$/\1/')"
fi

# Retrieve the name of the latest version of Dependency Check from the OWASP group
file="$(curl -s https://api.github.com/repos/jeremylong/DependencyCheck/releases/latest | grep browser_download_url | head -1 |sed -re 's/.*: "([^"]+)".*/\1/')"

# get the file version 
file_version="$(echo $file | sed 's/^.*[^0-9]\([0-9]*\.[0-9]*\.[0-9]*\).*$/\1/')"

# check if file file is newer and then download, if necessary
if [[ "x$version" != "x$file_version" ]]; then
    echo "Downloading latested version of the tool"
    # download file
    curl -LsS $file > dependencycheck.zip
    # remove old version
    rm -Rf dependency-check/LICENSE.txt dependency-check/NOTICE.txt dependency-check/README.md dependency-check/bin dependency-check/lib dependency-check/licenses dependency-check/plugins
    # unzip
    unzip -u dependencycheck.zip
    # remove old file
    rm dependencycheck.zip
fi

# run the run the check
dependency-check/bin/dependency-check.sh -s $scan_dir -o $scan_dir
