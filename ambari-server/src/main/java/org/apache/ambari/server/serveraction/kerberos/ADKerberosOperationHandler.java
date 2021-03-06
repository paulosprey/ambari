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


import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.exception.MethodInvocationException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;

import javax.naming.*;
import javax.naming.directory.*;
import javax.naming.ldap.Control;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Implementation of <code>KerberosOperationHandler</code> to created principal in Active Directory
 */
public class ADKerberosOperationHandler extends KerberosOperationHandler {

  private static Log LOG = LogFactory.getLog(ADKerberosOperationHandler.class);

  private static final String LDAP_CONTEXT_FACTORY_CLASS = "com.sun.jndi.ldap.LdapCtxFactory";

  public final static String KERBEROS_ENV_LDAP_URL = "ldap_url";
  public final static String KERBEROS_ENV_PRINCIPAL_CONTAINER_DN = "container_dn";
  public final static String KERBEROS_ENV_CREATE_ATTRIBUTES_TEMPLATE = "create_attributes_template";

  /**
   * A String containing the URL for the LDAP interface for the relevant Active Directory
   */
  private String ldapUrl = null;

  /**
   * A String containing the DN of the container to create new account in
   */
  private String principalContainerDn = null;

  /**
   * A String containing the Velocity template to use to generate the JSON structure declaring the
   * attributes to use to create new Active Directory accounts.
   * <p/>
   * If this value is null, a default template will be used.
   */
  private String createTemplate = null;

  /**
   * The relevant LDAP context, created upon opening this KerberosOperationHandler
   */
  private LdapContext ldapContext = null;

  /**
   * The relevant SearchControls, created upon opening this KerberosOperationHandler
   */
  private SearchControls searchControls = null;

  /**
   * VelocityEngine used to process the "create principal template" that is expected to generate
   * a JSON structure declaring the attributes of the Active Directory account
   */
  private VelocityEngine velocityEngine = null;

  /**
   * The Gson instance to use to convert the template-generated JSON structure to a Map of attribute
   * names to values.
   */
  private Gson gson = new Gson();

  /**
   * Prepares and creates resources to be used by this KerberosOperationHandler
   * <p/>
   * It is expected that this KerberosOperationHandler will not be used before this call.
   * <p/>
   * It is expected that the kerberosConfiguration Map has the following properties:
   * <ul>
   * <li>ldap_url - ldapUrl of ldap back end where principals would be created</li>
   * <li>container_dn - DN of the container in ldap back end where principals would be created</li>
   * </il>
   *
   * @param administratorCredentials a KerberosCredential containing the administrative credentials
   *                                 for the relevant KDC
   * @param realm                    a String declaring the default Kerberos realm (or domain)
   * @param kerberosConfiguration    a Map of key/value pairs containing data from the kerberos-env configuration set
   * @throws KerberosKDCConnectionException       if a connection to the KDC cannot be made
   * @throws KerberosAdminAuthenticationException if the administrator credentials fail to authenticate
   * @throws KerberosRealmException               if the realm does not map to a KDC
   * @throws KerberosOperationException           if an unexpected error occurred
   */
  @Override
  public void open(KerberosCredential administratorCredentials, String realm,
                   Map<String, String> kerberosConfiguration) throws KerberosOperationException {

    if (isOpen()) {
      close();
    }

    if (administratorCredentials == null) {
      throw new KerberosAdminAuthenticationException("administrator credentials not provided");
    }
    if (realm == null) {
      throw new KerberosRealmException("realm not provided");
    }
    if (kerberosConfiguration == null) {
      throw new KerberosRealmException("kerberos-env configuration may not be null");
    }

    this.ldapUrl = kerberosConfiguration.get(KERBEROS_ENV_LDAP_URL);
    if (this.ldapUrl == null) {
      throw new KerberosKDCConnectionException("ldapUrl not provided");
    }

    this.principalContainerDn = kerberosConfiguration.get(KERBEROS_ENV_PRINCIPAL_CONTAINER_DN);
    if (this.principalContainerDn == null) {
      throw new KerberosLDAPContainerException("principalContainerDn not provided");
    }

    setAdministratorCredentials(administratorCredentials);
    setDefaultRealm(realm);

    this.ldapContext = createLdapContext();
    this.searchControls = createSearchControls();

    this.createTemplate = kerberosConfiguration.get(KERBEROS_ENV_CREATE_ATTRIBUTES_TEMPLATE);

    this.velocityEngine = new VelocityEngine();
    this.velocityEngine.init();

    this.gson = new Gson();

    setOpen(true);
  }

  /**
   * Closes and cleans up any resources used by this KerberosOperationHandler
   * <p/>
   * It is expected that this KerberosOperationHandler will not be used after this call.
   */
  @Override
  public void close() throws KerberosOperationException {
    this.searchControls = null;
    this.velocityEngine = null;

    this.gson = null;

    if (this.ldapContext != null) {
      try {
        this.ldapContext.close();
      } catch (NamingException e) {
        throw new KerberosOperationException("Unexpected error", e);
      } finally {
        this.ldapContext = null;
      }
    }

    setOpen(false);
  }

  /**
   * Test to see if the specified principal exists in a previously configured KDC
   * <p/>
   * The implementation is specific to a particular type of KDC.
   *
   * @param principal a String containing the principal to test
   * @return true if the principal exists; false otherwise
   * @throws KerberosOperationException
   */
  @Override
  public boolean principalExists(String principal) throws KerberosOperationException {
    if (!isOpen()) {
      throw new KerberosOperationException("This operation handler has not been opened");
    }
    if (principal == null) {
      throw new KerberosOperationException("principal is null");
    }

    DeconstructedPrincipal deconstructPrincipal = deconstructPrincipal(principal);

    try {
      return (findPrincipalDN(deconstructPrincipal.getNormalizedPrincipal()) != null);
    } catch (NamingException ne) {
      throw new KerberosOperationException("can not check if principal exists: " + principal, ne);
    }
  }

  /**
   * Creates a new principal in a previously configured KDC
   * <p/>
   * The implementation is specific to a particular type of KDC.
   *
   * @param principal a String containing the principal to add
   * @param password  a String containing the password to use when creating the principal
   * @param service   a boolean value indicating whether the principal is to be created as a service principal or not
   * @return an Integer declaring the generated key number
   * @throws KerberosOperationException
   */
  @Override
  public Integer createPrincipal(String principal, String password, boolean service)
      throws KerberosOperationException {
    if (!isOpen()) {
      throw new KerberosOperationException("This operation handler has not been opened");
    }

    if (principal == null) {
      throw new KerberosOperationException("principal is null");
    }
    if (password == null) {
      throw new KerberosOperationException("principal password is null");
    }

    // TODO: (rlevas) pass components and realm in separately (AMBARI-9122)
    DeconstructedPrincipal deconstructedPrincipal = deconstructPrincipal(principal);

    String realm = deconstructedPrincipal.getRealm();
    if (realm == null) {
      realm = "";
    }

    Map<String, Object> context = new HashMap<String, Object>();
    context.put("normalized_principal", deconstructedPrincipal.getNormalizedPrincipal());
    context.put("principal_name", deconstructedPrincipal.getPrincipalName());
    context.put("principal_primary", deconstructedPrincipal.getPrimary());
    context.put("principal_instance", deconstructedPrincipal.getInstance());
    context.put("realm", realm);
    context.put("realm_lowercase", realm.toLowerCase());
    context.put("password", password);
    context.put("is_service", service);
    context.put("container_dn", this.principalContainerDn);
    context.put("principal_digest", DigestUtils.sha1Hex(deconstructedPrincipal.getNormalizedPrincipal()));

    Map<String, Object> data = processCreateTemplate(context);

    Attributes attributes = new BasicAttributes();
    String cn = null;

    if (data != null) {
      for (Map.Entry<String, Object> entry : data.entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();

        if ("unicodePwd".equals(key)) {
          if (value instanceof String) {
            try {
              attributes.put(new BasicAttribute("unicodePwd", String.format("\"%s\"", password).getBytes("UTF-16LE")));
            } catch (UnsupportedEncodingException ue) {
              throw new KerberosOperationException("Can not encode password with UTF-16LE", ue);
            }
          }
        } else {
          Attribute attribute = new BasicAttribute(key);
          if (value instanceof Collection) {
            for (Object object : (Collection) value) {
              attribute.add(object);
            }
          } else {
            attribute.add(value);

            if ("cn".equals(key) && (value != null)) {
              cn = value.toString();
            }
          }
          attributes.put(attribute);
        }
      }
    }

    if (cn == null) {
      cn = deconstructedPrincipal.getNormalizedPrincipal();
    }
    try {
      Name name = new CompositeName().add(String.format("cn=%s,%s", cn, principalContainerDn));
      ldapContext.createSubcontext(name, attributes);
    } catch (NamingException ne) {
      throw new KerberosOperationException("Can not create principal : " + principal, ne);
    }
    return 0;
  }

  /**
   * Updates the password for an existing principal in a previously configured KDC
   * <p/>
   * The implementation is specific to a particular type of KDC.
   *
   * @param principal a String containing the principal to update
   * @param password  a String containing the password to set
   * @return an Integer declaring the new key number
   * @throws KerberosOperationException
   */
  @Override
  public Integer setPrincipalPassword(String principal, String password) throws KerberosOperationException {
    if (!isOpen()) {
      throw new KerberosOperationException("This operation handler has not been opened");
    }
    if (principal == null) {
      throw new KerberosOperationException("principal is null");
    }
    if (password == null) {
      throw new KerberosOperationException("principal password is null");
    }

    DeconstructedPrincipal deconstructPrincipal = deconstructPrincipal(principal);

    try {
      String dn = findPrincipalDN(deconstructPrincipal.getNormalizedPrincipal());

      if (dn != null) {
        ldapContext.modifyAttributes(dn,
            new ModificationItem[]{
                new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("unicodePwd", String.format("\"%s\"", password).getBytes("UTF-16LE")))
            }
        );
      } else {
        throw new KerberosOperationException(String.format("Can not set password for principal %s: Not Found", principal));
      }
    } catch (NamingException e) {
      throw new KerberosOperationException(String.format("Can not set password for principal %s: %s", principal, e.getMessage()), e);
    } catch (UnsupportedEncodingException e) {
      throw new KerberosOperationException("Unsupported encoding UTF-16LE", e);
    }

    return 0;
  }

  /**
   * Removes an existing principal in a previously configured KDC
   * <p/>
   * The implementation is specific to a particular type of KDC.
   *
   * @param principal a String containing the principal to remove
   * @return true if the principal was successfully removed; otherwise false
   * @throws KerberosOperationException
   */
  @Override
  public boolean removePrincipal(String principal) throws KerberosOperationException {
    if (!isOpen()) {
      throw new KerberosOperationException("This operation handler has not been opened");
    }
    if (principal == null) {
      throw new KerberosOperationException("principal is null");
    }

    DeconstructedPrincipal deconstructPrincipal = deconstructPrincipal(principal);

    try {
      String dn = findPrincipalDN(deconstructPrincipal.getNormalizedPrincipal());

      if (dn != null) {
        ldapContext.destroySubcontext(dn);
      }
    } catch (NamingException e) {
      throw new KerberosOperationException(String.format("Can not remove principal %s: %s", principal, e.getMessage()), e);
    }

    return true;
  }

  @Override
  public boolean testAdministratorCredentials() throws KerberosOperationException {
    if (!isOpen()) {
      throw new KerberosOperationException("This operation handler has not been opened");
    }
    // If this KerberosOperationHandler was successfully opened, successful authentication has
    // already occurred.
    return true;
  }

  /**
   * Helper method to create the LDAP context needed to interact with the Active Directory.
   *
   * @return the relevant LdapContext
   * @throws KerberosKDCConnectionException       if a connection to the KDC cannot be made
   * @throws KerberosAdminAuthenticationException if the administrator credentials fail to authenticate
   * @throws KerberosRealmException               if the realm does not map to a KDC
   * @throws KerberosOperationException           if an unexpected error occurred
   */
  protected LdapContext createLdapContext() throws KerberosOperationException {
    KerberosCredential administratorCredentials = getAdministratorCredentials();

    Properties properties = new Properties();
    properties.put(Context.INITIAL_CONTEXT_FACTORY, LDAP_CONTEXT_FACTORY_CLASS);
    properties.put(Context.PROVIDER_URL, ldapUrl);
    properties.put(Context.SECURITY_PRINCIPAL, administratorCredentials.getPrincipal());
    properties.put(Context.SECURITY_CREDENTIALS, administratorCredentials.getPassword());
    properties.put(Context.SECURITY_AUTHENTICATION, "simple");
    properties.put(Context.REFERRAL, "follow");
    properties.put("java.naming.ldap.factory.socket", TrustingSSLSocketFactory.class.getName());

    try {
      return createInitialLdapContext(properties, null);
    } catch (CommunicationException e) {
      String message = String.format("Failed to communicate with the Active Directory at %s: %s", ldapUrl, e.getMessage());
      LOG.warn(message, e);
      throw new KerberosKDCConnectionException(message, e);
    } catch (AuthenticationException e) {
      String message = String.format("Failed to authenticate with the Active Directory at %s: %s", ldapUrl, e.getMessage());
      LOG.warn(message, e);
      throw new KerberosAdminAuthenticationException(message, e);
    } catch (NamingException e) {
      String error = e.getMessage();

      if ((error != null) && !error.isEmpty()) {
        String message = String.format("Failed to communicate with the Active Directory at %s: %s", ldapUrl, e.getMessage());
        LOG.warn(message, e);

        if (error.startsWith("Cannot parse url:")) {
          throw new KerberosKDCConnectionException(message, e);
        } else {
          throw new KerberosOperationException(message, e);
        }
      } else {
        throw new KerberosOperationException("Unexpected error condition", e);
      }
    }
  }

  /**
   * Helper method to create the LDAP context needed to interact with the Active Directory.
   * <p/>
   * This is mainly used to help with building mocks for test cases.
   *
   * @param properties environment used to create the initial DirContext.
   *                   Null indicates an empty environment.
   * @param controls   connection request controls for the initial context.
   *                   If null, no connection request controls are used.
   * @return the relevant LdapContext
   * @throws NamingException if a naming exception is encountered
   */
  protected LdapContext createInitialLdapContext(Properties properties, Control[] controls)
      throws NamingException {
    return new InitialLdapContext(properties, controls);
  }

  /**
   * Helper method to create the SearchControls instance
   *
   * @return the relevant SearchControls
   */
  protected SearchControls createSearchControls() {
    SearchControls searchControls = new SearchControls();
    searchControls.setSearchScope(SearchControls.ONELEVEL_SCOPE);
    searchControls.setReturningAttributes(new String[]{"cn"});
    return searchControls;
  }

  /**
   * Processes a Velocity template to generate a map of attributes and values to use to create
   * Active Directory accounts.
   * <p/>
   * If a template was not set, a default template will be used.
   *
   * @param context a map of properties to pass to the Velocity engine
   * @return a Map of attribute names and values to use for creating an Active Directory account
   * @throws KerberosOperationException if an error occurs processing the template.
   */
  protected Map<String, Object> processCreateTemplate(Map<String, Object> context)
      throws KerberosOperationException {

    if (velocityEngine == null) {
      throw new KerberosOperationException("The Velocity Engine must not be null");
    }
    if (gson == null) {
      throw new KerberosOperationException("The JSON parser must not be null");
    }

    Map<String, Object> data = null;
    String template;
    StringWriter stringWriter = new StringWriter();

    if ((createTemplate == null) || createTemplate.isEmpty()) {
      template = "{" +
          "\"objectClass\": [\"top\", \"person\", \"organizationalPerson\", \"user\"]," +
          "\"cn\": \"$principal_name\"," +
          "#if( $is_service )" +
          "  \"servicePrincipalName\": \"$principal_name\"," +
          "#end" +
          "\"userPrincipalName\": \"$normalized_principal\"," +
          "\"unicodePwd\": \"$password\"," +
          "\"accountExpires\": \"0\"," +
          "\"userAccountControl\": \"66048\"" +
          "}";
    } else {
      template = createTemplate;
    }

    try {
      if (velocityEngine.evaluate(new VelocityContext(context), stringWriter, "Active Directory principal create template", template)) {
        String json = stringWriter.toString();
        Type type = new TypeToken<Map<String, Object>>() {
        }.getType();

        data = gson.fromJson(json, type);
      }
    } catch (ParseErrorException e) {
      LOG.warn("Failed to parse Active Directory create principal template", e);
      throw new KerberosOperationException("Failed to parse Active Directory create principal template", e);
    } catch (MethodInvocationException e) {
      LOG.warn("Failed to process Active Directory create principal template", e);
      throw new KerberosOperationException("Failed to process Active Directory create principal template", e);
    } catch (ResourceNotFoundException e) {
      LOG.warn("Failed to process Active Directory create principal template", e);
      throw new KerberosOperationException("Failed to process Active Directory create principal template", e);
    }

    return data;
  }

  private String findPrincipalDN(String normalizedPrincipal) throws NamingException, KerberosOperationException {
    String dn = null;

    if (normalizedPrincipal != null) {
      NamingEnumeration<SearchResult> results = null;

      try {
        results = ldapContext.search(
            principalContainerDn,
            String.format("(userPrincipalName=%s)", normalizedPrincipal),
            searchControls
        );

        if ((results != null) && results.hasMore()) {
          SearchResult result = results.next();
          dn = result.getNameInNamespace();
        }
      } finally {
        try {
          if (results != null) {
            results.close();
          }
        } catch (NamingException ne) {
          // ignore, we can not do anything about it
        }
      }
    }

    return dn;
  }
}
