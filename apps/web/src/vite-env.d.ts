/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL of the Snipnet API, without a trailing slash. Empty or unset means same origin. */
  readonly VITE_API_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
