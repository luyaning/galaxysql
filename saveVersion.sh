#!/bin/sh

# This file is used to generate the package-info.java class that
# records the version, revision, branch, user, timestamp, and url

unset LANG
unset LC_CTYPE
unset LC_TIME
user="polardbx"
date=`date +'%F %T'`
current_path=`pwd`
case "`uname`" in
    Linux)
    bin_abs_path=$(readlink -f $(dirname $0))
    ;;
  *)
    bin_abs_path=`cd $(dirname $0); pwd`
    ;;
esac
echo "run saveVersion.sh script"
echo "cd to $bin_abs_path for workaround relative path"
cd $bin_abs_path

if [ -d .svn ]; then
  revision=`svn info | sed -n -e 's/Last Changed Rev: \(.*\)/\1/p'`
  url=`svn info node | sed -n -e 's/URL: \(.*\)/\1/p'`
  # Get canonical branch (branches/X, tags/X, or trunk)
  branch=`echo $url | sed -n -e 's,.*\(branches/.*\)$,\1,p' \
                             -e 's,.*\(tags/.*\)$,\1,p' \
                             -e 's,.*/trunk$,trunk,p'`
  version=`echo $url | sed -n -e 's,.*branches/\(.*\)$,\1,p' \
                             -e 's,.*tags/\(.*\)$,\1,p' \
                             -e 's,.*/trunk$,trunk,p'`
elif [ -d .git ]; then
  revision=`git log -1 --pretty=format:"%H"`
  hostname=`hostname`
  url="git@github.com:ApsaraDB/galaxysql.git"
  branch="Unknown"
  #url=`git remote -v | grep origin | grep fetch |  awk '{print $2}'`
  #branch=`git branch --no-color | grep '*' | awk '{print $2}'`
  case "`uname`" in
    Darwin)
      version=`cat pom.xml | head -n 10 | egrep -o '<version>5\..*?(-SNAPSHOT)?<' | cut -c 10- | awk -F'<' '{print $1}'`
      ;;
    *)
      version=`cat pom.xml | head -n 10 | grep -oP '<version>5\..*?(-SNAPSHOT)?<' | cut -c 10- | awk -F'<' '{print $1}'`
    ;;
  esac
else
  revision="Unknown"
  branch="Unknown"
  version="Unknown"
  url="file://$bin_abs_path"
fi

# for rpm
if [ "x${RELEASE}" != "x" ];then
  ec="echo $version | sed 's/SNAPSHOT/$RELEASE/g'"
  version=`eval $ec`
elif [ "x${FW_BRANCH_NAME}" != "x" ]; then
  # for fastwork read from rpm tag build name
  # ex : t-drds-server_A_5_1_28_1396535_20170906 , substring 1396535
  tmp_version=`echo ${FW_BRANCH_NAME##*_}`
  if [ "${#tmp_version}" == "8" ]; then
    tmp_version=`echo ${FW_BRANCH_NAME%_*}`
    tmp_version=`echo ${tmp_version##*_}`
    version="${tmp_version}"
  else
    # for fastwork build version
    if [ "x${GIT_TAG}" != "x" ]; then
      version=`echo ${GIT_TAG} | awk -F'_' '{printf "%s-%s",$1,$3}'`
    fi
  fi
else
  RELEASE=`date +%Y%m%d`
  ec="echo $version | sed 's/SNAPSHOT/$RELEASE/g'"
  version=`eval $ec`
fi

MD5SUM=$(which md5sum)
if [ ! -z $MD5SUM ] ; then
  srcChecksum=`find ./ -maxdepth 1 -name '*.sh' | LC_ALL=C sort | xargs md5sum | md5sum | cut -d ' ' -f 1`
fi

echo "server release version : $version"

cat << EOF | \
  sed -e "s/VERSION/$version/" -e "s/USER/$user/" -e "s/DATE/$date/" \
      -e "s|URL|$url|" -e "s/REV/$revision/" \
      -e "s|BRANCH|$branch|" -e "s/SRCCHECKSUM/$srcChecksum/" \
      > polardbx-common/src/main/java/com/alibaba/polardbx/common/utils/version/package-info.java
/*
 * Generated by saveVersion.sh
 */
@VersionAnnotation(version="VERSION", revision="REV", branch="BRANCH",
                         user="USER", date="DATE", url="URL",
                         srcChecksum="SRCCHECKSUM")
package com.alibaba.polardbx.common.utils.version;
EOF

echo "cd to $current_path for continue"
cd $current_path