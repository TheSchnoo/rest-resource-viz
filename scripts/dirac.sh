#!/usr/bin/env bash

set -ex

function lookup_chromium () {
    local platform=${1:-Mac}
    local position=$2
    local range=${3:-100}

    local platform_zip=

    if [[ "$platform" == Linux* ]]; then
        platform_zip="chrome-linux.zip"
    fi
    if [[ "$platform" == Win* ]]; then
        platform_zip="chrome-win32.zip"
    fi

    if [ -z "$position" ] ; then
        echo -n "Determining latest build for $platform..."
        position=`curl -s http://commondatastorage.googleapis.com/chromium-browser-snapshots/${platform}/LAST_CHANGE`
        echo "=> position is $position"
    fi

    local min_position=`expr ${position} - ${range}`
    for ((i=$min_position; i<=$position; i++)); do
        local url="http://commondatastorage.googleapis.com/chromium-browser-snapshots/$platform/$i/$platform_zip"
        if curl --output /dev/null --silent --head --fail "$url"; then
            echo $url
            break
        fi
    done
}

################
# SCRIPT START #
################

install_dir=
profile_dir=
chromium_open_url=
chromium_dir_name=chrome-linux
chromium_user_dir=dirac-profile

installed_file_name=.dirac-installed
installed_file_dir=$(find . -name $installed_file_name)

if [ ! -s "$installed_file_dir" ]; then

    echo "Installing Chromium Canary for Dirac..."

    temp_dir=$(mktemp -d dirac.XXXXXXX)

    install_dir=$temp_dir/$chromium_dir_name
    mkdir -p "$install_dir"

    profile_dir=$temp_dir/$chromium_user_dir
    mkdir -p "$profile_dir"

    github_coord="binaryage/dirac"
    dirac_repo="https://github.com/$github_coord"
    dirac_raw_repo="https://raw.githubusercontent.com/$github_coord"

    dirac_release_tag=$(curl -s https://api.github.com/repos/$github_coord/releases/latest | grep tag_name | cut -d ":" -f 2 | grep -Eoi '\"(.*)\"' | sed 's/[ \"]//g')
    dirac_chromium_version=$(curl -s $dirac_raw_repo/$dirac_release_tag/resources/unpacked/devtools/front_end/InspectorBackendCommands.js | head -n 1 | cut -d "=" -f 2 | sed -e "s/[';]//g")

    chromium_position=
    if [ ! -z "$dirac_chromium_version" ]; then
        chromium_position=$(curl -s "https://omahaproxy.appspot.com/deps.json?version=$dirac_chromium_version" | grep -Eoi '\"chromium_base_position\"\s*\:\s*\"[0-9]*\"' | cut -d ":" -f 2 | sed 's/[ \"]//g')
    fi

    if [ -z "$chromium_position" ]; then
        echo "Cannot fetch the Google Chromium version, try later."
        exit 1
    fi

    # platforms="Mac Linux_x64 Win Win_x64"
    # for platform in $platforms; do
    #     link=$(lookup_chromium $platform $chromium_position)
    #     echo "Latest Chromium link for $platform: $link"
    # done

    platform=

    # Detect the platform (similar to $OSTYPE)
    OS="$(uname)"
    case $OS in
        "Linux")
            if [ "$(uname -m)" != "x86_64" ]; then
                echo "Linux is supported, but only for x86_64 architectures at the moment."
                exit 3
            fi
            platform="Linux_x64"
            ;;
        "Darwin")
            OS="Mac"
            ;;
        *)
            if [[ "$OSTYPE" == "cygwin" ]]; then
                # POSIX compatibility layer and Linux environment emulation for Windows
                # This is untested, sorry about that.
                platform="Win"
            elif [[ "$OSTYPE" == "msys" ]]; then
                # Lightweight shell and GNU utilities compiled for Windows (part of MinGW)
                # This is untested, sorry about that.
                platform="Win"
            elif [[ "$OSTYPE" == "win32" ]]; then
                # This is untested, sorry about that.
                platform="Win"
            else
                echo "Cannot detect the target OS platform."
                exit 2
            fi
            ;;
    esac

    if [ $platform == "Win" ]; then
        echo "Sorry Windows is not supported at the moment."
        exit 3
    fi

    echo "Fetching $platform build..."
    link=$(lookup_chromium $platform $chromium_position)

    echo "Exploding to $install_dir..."
    old_dir=$(pwd)
    cd $temp_dir

    if curl -LOk $link; then
        file_name=$(basename $link)
        unzip "$file_name"
        rm -v "$file_name"
    fi
    cd "$old_dir"

    echo "Dirac $dirac_release_tag - Chromium $dirac_chromium_version for $platform" > "$temp_dir/$installed_file_name"
    chromium_open_url="https://chrome.google.com/webstore/detail/dirac-devtools/kbkdngfljkchidcjpnfcgcokkbhlkogi?hl=en"
else
    base_dir=$(dirname "$installed_file_dir")
    install_dir="$base_dir/$chromium_dir_name"
    echo "Chromium Canary for Dirac detected in $install_dir"
    profile_dir=$base_dir/dirac-profile
fi

chromium_port=9222
echo "Launching Chromium with remote debugging on port $chromium_port..."
"$install_dir/chrome" --remote-debugging-port=$chromium_port --no-first-run --user-data-dir="$profile_dir" $chromium_open_url
