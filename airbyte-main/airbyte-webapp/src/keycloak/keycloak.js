import Keycloak from 'keycloak-js';

const keycloak = new Keycloak({
  url: 'http://localhost:8081/',
  //url: 'https://authenticator.engeem.com/',

  realm: 'master',
 // realm: 'engeem',

  clientId: 'airbyte'
});

export { keycloak };