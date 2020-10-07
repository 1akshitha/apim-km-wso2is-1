/*
 *  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.is.key.manager.core.tokenmgt.issuers;

import org.apache.axis2.util.JavaUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.opensaml.saml.saml2.core.Assertion;
import org.osgi.service.component.annotations.Component;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.wso2.carbon.identity.base.IdentityConstants;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.oauth.OAuthUtil;
import org.wso2.carbon.identity.oauth.cache.CacheEntry;
import org.wso2.carbon.identity.oauth.cache.OAuthCache;
import org.wso2.carbon.identity.oauth.cache.OAuthCacheKey;
import org.wso2.carbon.identity.oauth.callback.OAuthCallback;
import org.wso2.carbon.identity.oauth.common.GrantType;
import org.wso2.carbon.identity.oauth.config.OAuthServerConfiguration;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2ScopeServerException;
import org.wso2.carbon.identity.oauth2.Oauth2ScopeConstants;
import org.wso2.carbon.identity.oauth2.authz.OAuthAuthzReqMessageContext;
import org.wso2.carbon.identity.oauth2.bean.Scope;
import org.wso2.carbon.identity.oauth2.dao.OAuthTokenPersistenceFactory;
import org.wso2.carbon.identity.oauth2.dto.OAuth2TokenValidationRequestDTO;
import org.wso2.carbon.identity.oauth2.grant.jwt.JWTConstants;
import org.wso2.carbon.identity.oauth2.model.AccessTokenDO;
import org.wso2.carbon.identity.oauth2.model.ResourceScopeCacheEntry;
import org.wso2.carbon.identity.oauth2.token.OAuthTokenReqMessageContext;
import org.wso2.carbon.identity.oauth2.validators.OAuth2TokenValidationMessageContext;
import org.wso2.carbon.identity.oauth2.validators.scope.ScopeValidator;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.api.UserStoreManager;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;
import org.wso2.is.key.manager.core.tokenmgt.handlers.ResourceConstants;
import org.wso2.is.key.manager.core.tokenmgt.util.TokenMgtUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.wso2.carbon.registry.core.jdbc.DumpConstants.RESOURCE;

/**
 * This class implements Scope Validator which represents the functions related to an scope issuer which
 * issues scopes based on user roles.
 */
@Component(name = "RoleBasedScopesIssuer", immediate = true, service = ScopeValidator.class)
public class RoleBasedScopesIssuer extends AbstractScopesIssuer implements ScopeValidator {

    private static Log log = LogFactory.getLog(RoleBasedScopesIssuer.class);
    private static final String DEFAULT_SCOPE_NAME = "default";
    private static final String PRESERVED_CASE_SENSITIVE_VARIABLE = "preservedCaseSensitive";
    private static final String ACCESS_TOKEN_DO = "AccessTokenDO";
    // The following constants are as same as the constants defined in
    // org.wso2.carbon.apimgt.keymgt.handlers.ResourceConstants.
    // If any changes are taking place in that these should also be updated accordingly.
    // Setting the "retrieveRolesFromUserStoreForScopeValidation" as a System property which is used when
    // skipping the scope role validation during token issuing using JWT bearer grant.
    public static final String CHECK_ROLES_FROM_SAML_ASSERTION = "checkRolesFromSamlAssertion";
    public static final String RETRIEVE_ROLES_FROM_USERSTORE_FOR_SCOPE_VALIDATION =
            "retrieveRolesFromUserStoreForScopeValidation";
    private static final String SCOPE_VALIDATOR_NAME = "Role based scope validator";
    private static final String OPENID = "openid";
    protected boolean rejectUnregisteredScopes = false;
    List<String> allowed;

    // set role based scopes issuer as the default
    private static final String ISSUER_PREFIX = "default";
    OAuthServerConfiguration oAuthServerConfiguration = OAuthServerConfiguration.getInstance();


    @Override
    public boolean validateScope(OAuthAuthzReqMessageContext oAuthAuthzReqMessageContext) throws
            IdentityOAuth2Exception {
        log.info("PREQA Validate scope method invoked at AuthorizationHandler");
        List<String> authScopes = getScopes(oAuthAuthzReqMessageContext, oAuthServerConfiguration.getAllowedScopes());
        oAuthAuthzReqMessageContext.setApprovedScope(authScopes.toArray(new String[authScopes.size()]));
        return true;
    }

    @Override
    public boolean validateScope(OAuthTokenReqMessageContext oAuthTokenReqMessageContext) throws
            IdentityOAuth2Exception {
        log.info("PREQA Validate scope method invoked at Access Token Issuer");
        List<String> authScopes = getScopes(oAuthTokenReqMessageContext, oAuthServerConfiguration.getAllowedScopes());
        oAuthTokenReqMessageContext.setScope(authScopes.toArray(new String[authScopes.size()]));
        return true;
    }

    @Override
    public boolean validateScope(OAuth2TokenValidationMessageContext oAuth2TokenValidationMessageContext) throws
            IdentityOAuth2Exception {
        log.info("Validate scope method invoked at Access Token Introspection");
        AccessTokenDO accessTokenDO = (AccessTokenDO) oAuth2TokenValidationMessageContext.getProperty(ACCESS_TOKEN_DO);
        if (accessTokenDO == null) {
            return false;
        }
        String resource = getResourceFromMessageContext(oAuth2TokenValidationMessageContext);
        // Return true if there is no resource to validate the token against.
        if (resource == null) {
            return true;
        }
        //Get the list of scopes associated with the access token
        String[] scopes = accessTokenDO.getScope();

        //If no scopes are associated with the token
        if (scopes == null || scopes.length == 0) {
            return true;
        }

        String resourceScope = null;
        int resourceTenantId = -1;

        boolean cacheHit = false;
        // Check the cache, if caching is enabled.
        OAuthCacheKey cacheKey = new OAuthCacheKey(resource);
        CacheEntry result = OAuthCache.getInstance().getValueFromCache(cacheKey);

        //Cache hit
        if (result != null && result instanceof ResourceScopeCacheEntry) {
            resourceScope = ((ResourceScopeCacheEntry) result).getScope();
            resourceTenantId = ((ResourceScopeCacheEntry) result).getTenantId();
            cacheHit = true;
        }

        // Cache was not hit. So retrieve from database.
        if (!cacheHit) {
            Pair<String, Integer> scopeMap = OAuthTokenPersistenceFactory.getInstance()
                    .getTokenManagementDAO().findTenantAndScopeOfResource(resource);

            if (scopeMap != null) {
                resourceScope = scopeMap.getLeft();
                resourceTenantId = scopeMap.getRight();
            }

            cacheKey = new OAuthCacheKey(resource);
            ResourceScopeCacheEntry cacheEntry = new ResourceScopeCacheEntry(resourceScope);
            cacheEntry.setTenantId(resourceTenantId);
            //Store resourceScope in cache even if it is null (to avoid database calls when accessing resources for
            //which scopes haven't been defined).
            OAuthCache.getInstance().addToCache(cacheKey, cacheEntry);
        }

        //Return TRUE if - There does not exist a scope definition for the resource
        if (resourceScope == null) {
            if (log.isDebugEnabled()) {
                log.debug("Resource '" + resource + "' is not protected with a scope");
            }
            return true;
        }

        List<String> scopeList = new ArrayList<>(Arrays.asList(scopes));

        // If the access token does not bear the scope required for accessing the Resource.
        if (!scopeList.contains(resourceScope)) {
            if (log.isDebugEnabled() && IdentityUtil.isTokenLoggable(IdentityConstants.IdentityTokens.ACCESS_TOKEN)) {
                log.debug("Access token '" + accessTokenDO.getAccessToken() + "' does not bear the scope '" +
                        resourceScope + "'");
            }
            return false;
        }

        // If a federated user and CHECK_ROLES_FROM_SAML_ASSERTION system property is set to true,
        // or if a federated user and RETRIEVE_ROLES_FROM_USERSTORE_FOR_SCOPE_VALIDATION system property is false,
        // avoid validating user roles.
        // This system property is set at server start using -D option, Thus will be a permanent property.
        if (accessTokenDO.getAuthzUser().isFederatedUser()
                && (Boolean.parseBoolean(System.getProperty(CHECK_ROLES_FROM_SAML_ASSERTION)) ||
                !(Boolean.parseBoolean(System.getProperty(RETRIEVE_ROLES_FROM_USERSTORE_FOR_SCOPE_VALIDATION))))) {
            return true;
        }

        try {
            /*User authzUser = accessTokenDO.getAuthzUser();
            int tenantId = getTenantId(authzUser);
            String[] userRoles = getUserRoles(authzUser);*/
            AuthenticatedUser authenticatedUser = OAuthUtil
                    .getAuthenticatedUser(oAuth2TokenValidationMessageContext.getResponseDTO().getAuthorizedUser());
            String clientId = accessTokenDO.getConsumerKey();
            List<String> requestedScopes = Arrays.asList(scopes);
            List<String> allowedScopes = oAuthServerConfiguration.getAllowedScopes();
            List<String> authorizedScopes = null;

            String[] userRoles = null;
            Map<String, String> appScopes = getAppScopes(clientId, authenticatedUser, requestedScopes);
            if (appScopes != null) {
                //If no scopes can be found in the context of the application
                if (isAppScopesEmpty(appScopes, clientId)) {
                    authorizedScopes = getAllowedScopes(allowedScopes, requestedScopes);
                    oAuth2TokenValidationMessageContext.getResponseDTO().setScope(authorizedScopes.toArray(
                            new String[authorizedScopes.size()]));
                    return true;
                }
                userRoles = getUserRoles(authenticatedUser);
                authorizedScopes = getAuthorizedScopes(userRoles, requestedScopes, appScopes, allowedScopes);
                oAuth2TokenValidationMessageContext.getResponseDTO().setScope(authorizedScopes.toArray(
                        new String[authorizedScopes.size()]));
            }
            if (ArrayUtils.isEmpty(userRoles)) {
                if (log.isDebugEnabled()) {
                    log.debug("No roles associated for the user " + authenticatedUser.getUserName());
                }
                return false;
            }


        } catch (Exception e) {
            //Log and return since we do not want to stop issuing the token in case of scope validation failures.
            log.error("Error when getting the tenant's UserStoreManager or when getting roles of user ", e);
            return false;
        }
        return true;
    }

    protected Set<Scope> retrieveScopes(int tenantId) throws IdentityOAuth2ScopeServerException {
        return OAuthTokenPersistenceFactory.getInstance().getOAuthScopeDAO().getScopes(tenantId,
                Oauth2ScopeConstants.DEFAULT_SCOPE_BINDING);
    }

    @Override
    public String getName() {
        return SCOPE_VALIDATOR_NAME;
    }

    @Override
    public String getPrefix() {
        return ISSUER_PREFIX;
    }


    /**
     * Extract the resource from the access token validation request message
     *
     * @param messageContext Message context of the token validation request
     * @return resource
     */
    private String getResourceFromMessageContext(OAuth2TokenValidationMessageContext messageContext) {

        String resource = null;
        if (messageContext.getRequestDTO().getContext() != null) {
            // Iterate the array of context params to find the 'resource' context param.
            for (OAuth2TokenValidationRequestDTO.TokenValidationContextParam resourceParam :
                    messageContext.getRequestDTO().getContext()) {
                // If the context param is the resource that is being accessed
                if (resourceParam != null && RESOURCE.equals(resourceParam.getKey())) {
                    resource = resourceParam.getValue();
                    break;
                }
            }
        }
        return resource;
    }

    public List<String> getScopes(OAuthAuthzReqMessageContext oAuthAuthzReqMessageContext, List<String> allowedScopes) {

        List<String> authorizedScopes = null;
        List<String> requestedScopes = new ArrayList<>();
        if (oAuthAuthzReqMessageContext.getApprovedScope() != null) {
            requestedScopes = Arrays.asList(oAuthAuthzReqMessageContext.getApprovedScope());
        }
        String clientId = oAuthAuthzReqMessageContext.getAuthorizationReqDTO().getConsumerKey();
        AuthenticatedUser authenticatedUser = oAuthAuthzReqMessageContext.getAuthorizationReqDTO().getUser();

        Map<String, String> appScopes = getAppScopes(clientId, authenticatedUser, requestedScopes);
        if (appScopes != null) {
            //If no scopes can be found in the context of the application
            if (isAppScopesEmpty(appScopes, clientId)) {
                return getAllowedScopes(allowedScopes, requestedScopes);
            }
            String[] userRoles = getUserRoles(authenticatedUser);
            authorizedScopes = getAuthorizedScopes(userRoles, requestedScopes, appScopes, allowedScopes);
        }
        return authorizedScopes;
    }

    /**
     * This method is used to retrieve authorized scopes with respect to an authorization callback.
     *
     * @param scopeValidationCallback Authorization callback to validate scopes
     * @return authorized scopes list
     */
    @Override
    public List<String> getScopes(OAuthCallback scopeValidationCallback, List<String> allowedScopes) {

        List<String> authorizedScopes = null;
        List<String> requestedScopes = Arrays.asList(scopeValidationCallback.getRequestedScope());
        String clientId = scopeValidationCallback.getClient();
        AuthenticatedUser authenticatedUser = scopeValidationCallback.getResourceOwner();

        Map<String, String> appScopes = getAppScopes(clientId, authenticatedUser, requestedScopes);
        if (appScopes != null) {
            //If no scopes can be found in the context of the application
            if (isAppScopesEmpty(appScopes, clientId)) {
                return getAllowedScopes(allowedScopes, requestedScopes);
            }
            String[] userRoles = getUserRoles(authenticatedUser);
            authorizedScopes = getAuthorizedScopes(userRoles, requestedScopes, appScopes, allowedScopes);
        }
        return authorizedScopes;
    }

    /**
     * This method is used to retrieve the authorized scopes with respect to a token.
     *
     * @param tokReqMsgCtx token message context
     * @return authorized scopes list
     */
    @Override
    public List<String> getScopes(OAuthTokenReqMessageContext tokReqMsgCtx, List<String> allowedScopes) {

        List<String> authorizedScopes = null;
        List<String> requestedScopes = Arrays.asList(tokReqMsgCtx.getScope());
        log.info("PREQA Requested scopes : " + requestedScopes);
        String clientId = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getClientId();
        AuthenticatedUser authenticatedUser = tokReqMsgCtx.getAuthorizedUser();
        log.info("PREQA Authenticated user name : " + authenticatedUser.getUserName());
        Map<String, String> appScopes = getAppScopes(clientId, authenticatedUser, requestedScopes);
        if (appScopes != null) {
            //If no scopes can be found in the context of the application
            if (isAppScopesEmpty(appScopes, clientId)) {
                return getAllowedScopes(allowedScopes, requestedScopes);
            }

            String grantType = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getGrantType();
            String[] userRoles = null;

            // If GrantType is SAML20_BEARER and CHECK_ROLES_FROM_SAML_ASSERTION is true, or if GrantType is
            // JWT_BEARER and retrieveRolesFromUserStoreForScopeValidation system property is true,
            // use user roles from assertion or jwt otherwise use roles from userstore.
            String isSAML2Enabled = System.getProperty(ResourceConstants.CHECK_ROLES_FROM_SAML_ASSERTION);
            String isRetrieveRolesFromUserStoreForScopeValidation = System
                    .getProperty(ResourceConstants.RETRIEVE_ROLES_FROM_USERSTORE_FOR_SCOPE_VALIDATION);
            if (GrantType.SAML20_BEARER.toString().equals(grantType) && Boolean.parseBoolean(isSAML2Enabled)) {
                Assertion assertion = (Assertion) tokReqMsgCtx.getProperty(ResourceConstants.SAML2_ASSERTION);
                userRoles = getRolesFromAssertion(assertion);
            } else if (JWTConstants.OAUTH_JWT_BEARER_GRANT_TYPE.equals(grantType) && !(Boolean
                    .parseBoolean(isRetrieveRolesFromUserStoreForScopeValidation))) {
                AuthenticatedUser user = tokReqMsgCtx.getAuthorizedUser();
                Map<ClaimMapping, String> userAttributes = user.getUserAttributes();
                if (tokReqMsgCtx.getProperty(ResourceConstants.ROLE_CLAIM) != null) {
                    userRoles = getRolesFromUserAttribute(userAttributes,
                            tokReqMsgCtx.getProperty(ResourceConstants.ROLE_CLAIM).toString());
                }
            } else {
                userRoles = getUserRoles(authenticatedUser);
            }
            authorizedScopes = getAuthorizedScopes(userRoles, requestedScopes, appScopes, allowedScopes);
            log.info("PREQA Authorized scope  : " + authorizedScopes);
        }
        return authorizedScopes;
    }

    protected List<String> getScopewithDefaultBinding(AuthenticatedUser authenticatedUser, String[] requestedScopes)
            throws IdentityOAuth2Exception {

        String[] scopes = null;
        List<String> requestedScopesList = Arrays.asList(requestedScopes);
        List<String> scopesToAuthorize = new ArrayList<>();
        if (log.isDebugEnabled()) {
            log.debug("Requested scopes :" + Arrays.toString(requestedScopes));
        }
        int tenantId = IdentityTenantUtil.getTenantId(authenticatedUser.getTenantDomain());
        try {
            // Get only the scopes with default binding. These scopes are mapped to roles.
            Set<Scope> retrievedScopes = retrieveScopes(tenantId);
            if (retrievedScopes == null || retrievedScopes.isEmpty()) {
                // if there are no scopes with default binding type, no additional validation is done.
                // This behavior could be changed by configuring KEY_REJECT_UNREGISTERED_SCOPES element
                if (!rejectUnregisteredScopes) {
                    return null;
                }
            } else {
                for (Scope scope : retrievedScopes) {
                    if (requestedScopesList.contains(scope.getName())) {
                        scopesToAuthorize.add(scope.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.error(e);
        }
        return scopesToAuthorize;
    }

    /**
     * This method is used to get roles list of the user.
     *
     * @param authenticatedUser Authenticated user
     * @return roles list
     */
    private String[] getUserRoles(AuthenticatedUser authenticatedUser) {

        String[] userRoles = null;
        String tenantDomain;
        String username;
        if (authenticatedUser.isFederatedUser()) {
            tenantDomain = MultitenantUtils.getTenantDomain(authenticatedUser.getAuthenticatedSubjectIdentifier());
            username = MultitenantUtils.getTenantAwareUsername(authenticatedUser.getAuthenticatedSubjectIdentifier());
        } else {
            tenantDomain = authenticatedUser.getTenantDomain();
            username = authenticatedUser.getUserName();
        }
        String userStoreDomain = authenticatedUser.getUserStoreDomain();
        RealmService realmService = getRealmService();
        try {
            int tenantId = realmService.getTenantManager().getTenantId(tenantDomain);
            // If tenant Id is not set in the tokenReqContext, deriving it from username.
            if (tenantId == 0 || tenantId == -1) {
                tenantId = getTenantIdOfUser(username);
            }
            UserStoreManager userStoreManager = realmService.getTenantUserRealm(tenantId).getUserStoreManager();
            String endUsernameWithDomain = addDomainToName(username, userStoreDomain);
            userRoles = userStoreManager.getRoleListOfUser(endUsernameWithDomain);

        } catch (UserStoreException e) {
            //Log and return since we do not want to stop issuing the token in case of scope validation failures.
            log.error("Error when getting the tenant's UserStoreManager or when getting roles of user ", e);
        }
        return userRoles;
    }

    /**
     * This method is used to get authorized scopes for user from the requested scopes based on roles.
     *
     * @param userRoles       Roles list of user
     * @param requestedScopes Requested scopes
     * @param appScopes       Scopes of the Application
     * @return authorized scopes list
     */
    private List<String> getAuthorizedScopes(String[] userRoles, List<String> requestedScopes,
                                             Map<String, String> appScopes, List<String> allowedScopes) {

        List<String> defaultScope = new ArrayList<>();
        defaultScope.add(DEFAULT_SCOPE_NAME);


        if (userRoles == null || userRoles.length == 0) {
            userRoles = new String[0];
        }

        List<String> authorizedScopes = new ArrayList<>();
        String preservedCaseSensitiveValue = System.getProperty(PRESERVED_CASE_SENSITIVE_VARIABLE);
        boolean preservedCaseSensitive = JavaUtils.isTrueExplicitly(preservedCaseSensitiveValue);
        List<String> userRoleList;
        if (preservedCaseSensitive) {
            userRoleList = Arrays.asList(userRoles);
        } else {
            userRoleList = new ArrayList<>();
            for (String aRole : userRoles) {
                userRoleList.add(aRole.toLowerCase(Locale.getDefault()));
            }
        }

        //Iterate the requested scopes list.
        for (String scope : requestedScopes) {
            //Get the set of roles associated with the requested scope.
            String roles = appScopes.get(scope);
            //If the scope has been defined in the context of the App and if roles have been defined for the scope
            if (roles != null && roles.length() != 0) {
                List<String> roleList = new ArrayList<>();
                for (String aRole : roles.split(",")) {
                    if (preservedCaseSensitive) {
                        roleList.add(aRole.trim());
                    } else {
                        roleList.add(aRole.trim().toLowerCase(Locale.getDefault()));
                    }
                }
                //Check if user has at least one of the roles associated with the scope
                roleList.retainAll(userRoleList);
                if (!roleList.isEmpty()) {
                    authorizedScopes.add(scope);
                }
            } else if (appScopes.containsKey(scope) || isAllowedScope(allowedScopes, scope)) {
                //The requested scope is defined for the context of the App but no roles have been associated with the
                //scope OR the scope string starts with 'device_'
                authorizedScopes.add(scope);
            }
        }

        return (!authorizedScopes.isEmpty()) ? authorizedScopes : defaultScope;
    }

    /**
     * Extract the roles from the user attributes.
     *
     * @param userAttributes retrieved from the token
     * @return roles
     */
    private String[] getRolesFromUserAttribute(Map<ClaimMapping, String> userAttributes, String roleClaim) {

        for (Iterator<Map.Entry<ClaimMapping, String>> iterator = userAttributes.entrySet().iterator(); iterator
                .hasNext(); ) {
            Map.Entry<ClaimMapping, String> entry = iterator.next();
            if (roleClaim.equals(entry.getKey().getLocalClaim().getClaimUri()) && StringUtils
                    .isNotBlank(entry.getValue())) {
                return entry.getValue().replace("\\/", "/").
                        replace("[", "").replace("]", "").
                        replace("\"", "").split(FrameworkUtils.getMultiAttributeSeparator());
            }
        }
        return new String[0];
    }


    /**
     * Add domain to name
     *
     * @param username   Username
     * @param domainName domain name
     * @return String
     */
    protected String addDomainToName(String username, String domainName) {
        return UserCoreUtil.addDomainToName(username, domainName);
    }

    /**
     * Get roles from assertion
     *
     * @param assertion Assertion
     * @return String[]
     */
    protected String[] getRolesFromAssertion(Assertion assertion) {
        return TokenMgtUtil.getRolesFromAssertion(assertion);
    }
}
