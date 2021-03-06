/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.serveraction.kerberos;

/**
 * KerberosActionDataFile declares the default data file name and the common record column names
 * for the Kerberos action (metadata) data files.
 */
public class KerberosActionDataFile {
  public static final String DATA_FILE_NAME = "index.dat";

  public static final String HOSTNAME = "hostname";
  public static final String SERVICE = "service";
  public static final String COMPONENT = "component";
  public static final String PRINCIPAL = "principal";
  public static final String PRINCIPAL_TYPE = "principal_type";
  public static final String PRINCIPAL_CONFIGURATION = "principal_configuration";
  public static final String KEYTAB_FILE_PATH = "keytab_file_path";
  public static final String KEYTAB_FILE_OWNER_NAME = "keytab_file_owner_name";
  public static final String KEYTAB_FILE_OWNER_ACCESS = "keytab_file_owner_access";
  public static final String KEYTAB_FILE_GROUP_NAME = "keytab_file_group_name";
  public static final String KEYTAB_FILE_GROUP_ACCESS = "keytab_file_group_access";
  public static final String KEYTAB_FILE_CONFIGURATION = "keytab_file_configuration";
}
