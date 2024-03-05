import { lazy, Suspense } from "react";
import { createRoot } from "react-dom/client";
import Keycloak from 'keycloak-js';

import "react-reflex/styles.css";
import { isCloudApp } from "core/utils/app";
import { loadDatadog } from "core/utils/datadog";
import { loadOsano } from "core/utils/dataPrivacy";

import "./dayjs-setup";
import "./scss/global.scss";

// We do not follow default config approach since we want to init datadog asap
loadDatadog();

// In Cloud load the Osano script (GDPR consent tool before anything else)
if (isCloudApp()) {
  loadOsano();
}

const CloudApp = lazy(() => import(`packages/cloud/App`));
const App = lazy(() => import(`./App`));

const keycloak = new Keycloak({
  //url: 'http://localhost:8081/',
  url: 'https://authenticator.engeem.com/',
  //realm: 'master',
  realm: 'engeem',
  clientId: 'airbyte'
});

keycloak.init({
  onLoad: 'login-required'
}).then((authenticated) => {
  if (authenticated) {
    const root = createRoot(document.getElementById("root")!);
    root.render(<Suspense fallback={null}>{isCloudApp() ? <CloudApp /> : <App />}</Suspense>);
  } else {https://github.com/IsraelBoka/airbyte-engeem
    console.error("Failed to authenticate with Keycloak");
  }
});

