import {appBasePath} from "../app/utils/base-path.util";

export const environment = {
    production: true,
    // Relative to the page: the production build is served by the same Micronaut jar it talks to, at the root or
    // under the context path the backend wrote into index.html's base href (e.g. /alpdesk-ai behind a reverse proxy).
    apiUrl: appBasePath(document.baseURI),
};
