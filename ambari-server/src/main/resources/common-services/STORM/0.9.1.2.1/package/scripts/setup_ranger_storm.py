#!/usr/bin/env python
"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

"""

import sys
import fileinput
import subprocess
import json
import re
import os
from resource_management import *
from resource_management.core.logger import Logger
from resource_management.libraries.functions.ranger_functions import Rangeradmin

def setup_ranger_storm(env):
  import params
  env.set_params(params)

  if params.has_ranger_admin and params.security_enabled:

    environment = {"no_proxy": format("{params.ambari_server_hostname}")}

    Execute(('curl', '-kf', '-x', "", '--retry', '10', params.driver_curl_source, '-o',
            params.downloaded_custom_connector),
            not_if=format("test -f {params.downloaded_custom_connector}"),
            path=["/bin", "/usr/bin/"],
            environment=environment,
            sudo=True)

    if not os.path.isfile(params.driver_curl_target):
      Execute(('cp', '--remove-destination', params.downloaded_custom_connector, params.driver_curl_target),
              path=["/bin", "/usr/bin/"],
              sudo=True)

    try:
      command = 'hdp-select status storm-nimbus'
      return_code, hdp_output = shell.call(command)
    except Exception, e:
      Logger.error(str(e))
      raise Fail('Unable to execute hdp-select command to retrieve the version.')

    if return_code != 0:
      raise Fail('Unable to determine the current version because of a non-zero return code of {0}'.format(str(return_code)))

    cmd_split = hdp_output.strip().split( ) 
    hdp_version = cmd_split[2]

    match = re.match('[0-9]+.[0-9]+.[0-9]+.[0-9]+-[0-9]+', hdp_version)

    if match is None:
      raise Fail('Failed to get extracted version')

    file_path = '/usr/hdp/'+ hdp_version +'/ranger-storm-plugin/install.properties'

    ranger_storm_dict = ranger_storm_properties(params)
    storm_repo_data = storm_repo_properties(params)        

    write_properties_to_file(file_path, ranger_storm_dict)

    if params.enable_ranger_storm:            
      cmd = format('cd /usr/hdp/{hdp_version}/ranger-storm-plugin/ && sh enable-storm-plugin.sh')
      ranger_adm_obj = Rangeradmin(url=ranger_storm_dict['POLICY_MGR_URL'])
      response_code, response_recieved = ranger_adm_obj.check_ranger_login_urllib2(ranger_storm_dict['POLICY_MGR_URL'] + '/login.jsp', 'test:test')

      if response_code is not None and response_code == 200:      
        ambari_ranger_admin = params.config['configurations']['ranger-env']['ranger_admin_username']
        ambari_ranger_password = params.config['configurations']['ranger-env']['ranger_admin_password']
        ambari_ranger_admin,ambari_ranger_password = ranger_adm_obj.create_ambari_admin_user(ambari_ranger_admin, ambari_ranger_password, 'admin:admin')
        ambari_username_password_for_ranger = ambari_ranger_admin + ':' + ambari_ranger_password
        if ambari_ranger_admin != '' and ambari_ranger_password != '':
          repo = ranger_adm_obj.get_repository_by_name_urllib2(ranger_storm_dict['REPOSITORY_NAME'], 'storm', 'true', ambari_username_password_for_ranger)
          if repo and repo['name'] == ranger_storm_dict['REPOSITORY_NAME']:
            Logger.info('STORM Repository exist')
          else:
            response = ranger_adm_obj.create_repository_urllib2(storm_repo_data, ambari_username_password_for_ranger)
            if response is not None:
              Logger.info('STORM Repository created in Ranger Admin')
            else:
              Logger.info('STORM Repository creation failed in Ranger Admin')
        else:
          Logger.info('Ambari admin username and password are blank ')
      else:
        Logger.info('Ranger service is not started on given host')                                        
    else:
      cmd = format('cd /usr/hdp/{hdp_version}/ranger-storm-plugin/ && sh disable-storm-plugin.sh')

    Execute(cmd, environment={'JAVA_HOME': params.java64_home}, logoutput=True)            
  else:
    Logger.info('Ranger admin not installed or security is not enabled')


def write_properties_to_file(file_path, value):
  for key in value:
    modify_config(file_path, key, value[key])


def modify_config(filepath, variable, setting):
  var_found = False
  already_set = False
  V=str(variable)
  S=str(setting)
  # use quotes if setting has spaces #
  if ' ' in S:
    S = '%s' % S

  for line in fileinput.input(filepath, inplace = 1):
    # process lines that look like config settings #
    if not line.lstrip(' ').startswith('#') and '=' in line:
      _infile_var = str(line.split('=')[0].rstrip(' '))
      _infile_set = str(line.split('=')[1].lstrip(' ').rstrip())
      # only change the first matching occurrence #
      if var_found == False and _infile_var.rstrip(' ') == V:
        var_found = True
        # don't change it if it is already set #
        if _infile_set.lstrip(' ') == S:
          already_set = True
        else:
          line = "%s=%s\n" % (V, S)

    sys.stdout.write(line)

  # Append the variable if it wasn't found #
  if not var_found:
    with open(filepath, "a") as f:
      f.write("%s=%s\n" % (V, S))
  elif already_set == True:
    pass
  else:
    pass

  return

def ranger_storm_properties(params):
  ranger_storm_properties = dict()

  ranger_storm_properties['POLICY_MGR_URL']           = params.config['configurations']['admin-properties']['policymgr_external_url']
  ranger_storm_properties['SQL_CONNECTOR_JAR']        = params.config['configurations']['admin-properties']['SQL_CONNECTOR_JAR']
  ranger_storm_properties['XAAUDIT.DB.FLAVOUR']       = params.config['configurations']['admin-properties']['DB_FLAVOR']
  ranger_storm_properties['XAAUDIT.DB.DATABASE_NAME'] = params.config['configurations']['admin-properties']['audit_db_name']
  ranger_storm_properties['XAAUDIT.DB.USER_NAME']     = params.config['configurations']['admin-properties']['audit_db_user']
  ranger_storm_properties['XAAUDIT.DB.PASSWORD']      = params.config['configurations']['admin-properties']['audit_db_password']
  ranger_storm_properties['XAAUDIT.DB.HOSTNAME']      = params.config['configurations']['admin-properties']['db_host']
  ranger_storm_properties['REPOSITORY_NAME']          = str(params.config['clusterName']) + '_storm'

  ranger_storm_properties['XAAUDIT.DB.IS_ENABLED']   = params.config['configurations']['ranger-storm-plugin-properties']['XAAUDIT.DB.IS_ENABLED']

  ranger_storm_properties['XAAUDIT.HDFS.IS_ENABLED'] = params.config['configurations']['ranger-storm-plugin-properties']['XAAUDIT.HDFS.IS_ENABLED']
  ranger_storm_properties['XAAUDIT.HDFS.DESTINATION_DIRECTORY'] = params.config['configurations']['ranger-storm-plugin-properties']['XAAUDIT.HDFS.DESTINATION_DIRECTORY']
  ranger_storm_properties['XAAUDIT.HDFS.LOCAL_BUFFER_DIRECTORY'] = params.config['configurations']['ranger-storm-plugin-properties']['XAAUDIT.HDFS.LOCAL_BUFFER_DIRECTORY']
  ranger_storm_properties['XAAUDIT.HDFS.LOCAL_ARCHIVE_DIRECTORY'] = params.config['configurations']['ranger-storm-plugin-properties']['XAAUDIT.HDFS.LOCAL_ARCHIVE_DIRECTORY']
  ranger_storm_properties['XAAUDIT.HDFS.DESTINTATION_FILE'] = params.config['configurations']['ranger-storm-plugin-properties']['XAAUDIT.HDFS.DESTINTATION_FILE']
  ranger_storm_properties['XAAUDIT.HDFS.DESTINTATION_FLUSH_INTERVAL_SECONDS'] = params.config['configurations']['ranger-storm-plugin-properties']['XAAUDIT.HDFS.DESTINTATION_FLUSH_INTERVAL_SECONDS']
  ranger_storm_properties['XAAUDIT.HDFS.DESTINTATION_ROLLOVER_INTERVAL_SECONDS'] = params.config['configurations']['ranger-storm-plugin-properties']['XAAUDIT.HDFS.DESTINTATION_ROLLOVER_INTERVAL_SECONDS']
  ranger_storm_properties['XAAUDIT.HDFS.DESTINTATION_OPEN_RETRY_INTERVAL_SECONDS'] = params.config['configurations']['ranger-storm-plugin-properties']['XAAUDIT.HDFS.DESTINTATION_OPEN_RETRY_INTERVAL_SECONDS']
  ranger_storm_properties['XAAUDIT.HDFS.LOCAL_BUFFER_FILE'] = params.config['configurations']['ranger-storm-plugin-properties']['XAAUDIT.HDFS.LOCAL_BUFFER_FILE']
  ranger_storm_properties['XAAUDIT.HDFS.LOCAL_BUFFER_FLUSH_INTERVAL_SECONDS'] = params.config['configurations']['ranger-storm-plugin-properties']['XAAUDIT.HDFS.LOCAL_BUFFER_FLUSH_INTERVAL_SECONDS']
  ranger_storm_properties['XAAUDIT.HDFS.LOCAL_BUFFER_ROLLOVER_INTERVAL_SECONDS'] = params.config['configurations']['ranger-storm-plugin-properties']['XAAUDIT.HDFS.LOCAL_BUFFER_ROLLOVER_INTERVAL_SECONDS']
  ranger_storm_properties['XAAUDIT.HDFS.LOCAL_ARCHIVE_MAX_FILE_COUNT'] = params.config['configurations']['ranger-storm-plugin-properties']['XAAUDIT.HDFS.LOCAL_ARCHIVE_MAX_FILE_COUNT']
  

  ranger_storm_properties['SSL_KEYSTORE_FILE_PATH'] = params.config['configurations']['ranger-storm-plugin-properties']['SSL_KEYSTORE_FILE_PATH']
  ranger_storm_properties['SSL_KEYSTORE_PASSWORD'] = params.config['configurations']['ranger-storm-plugin-properties']['SSL_KEYSTORE_PASSWORD']
  ranger_storm_properties['SSL_TRUSTSTORE_FILE_PATH'] = params.config['configurations']['ranger-storm-plugin-properties']['SSL_TRUSTSTORE_FILE_PATH']
  ranger_storm_properties['SSL_TRUSTSTORE_PASSWORD'] = params.config['configurations']['ranger-storm-plugin-properties']['SSL_TRUSTSTORE_PASSWORD']

  return ranger_storm_properties


def storm_repo_properties(params):

  storm_ui_server_host = params.config['clusterHostInfo']['storm_ui_server_hosts'][0]

  config_dict = dict()
  config_dict['username'] = params.config['configurations']['ranger-storm-plugin-properties']['REPOSITORY_CONFIG_USERNAME']
  config_dict['password'] = params.config['configurations']['ranger-storm-plugin-properties']['REPOSITORY_CONFIG_PASSWORD']
  config_dict['nimbus.url'] = 'http://' + storm_ui_server_host + ':' + str(params.config['configurations']['storm-site']['ui.port'])
  config_dict['commonNameForCertificate'] = params.config['configurations']['ranger-storm-plugin-properties']['common.name.for.certificate']


  repo = dict()
  repo['isActive'] = "true"
  repo['config'] = json.dumps(config_dict)
  repo['description'] = "storm repo"
  repo['name'] = str(params.config['clusterName']) + "_storm"
  repo['repositoryType'] = "Storm"
  repo['assetType'] = '6'

  data = json.dumps(repo)

  return data    
