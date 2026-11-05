// Build settings. Secrets come from src/secrets.ts (git-ignored; copy src/secrets.example.ts).
import { BAATO_ACCESS_TOKEN, TRACKME_API_SECRET } from './secrets';

/** DEMO ONLY: the example logs in by itself with the API secret. A real app gets the token from its own backend. */
export const API_SECRET = TRACKME_API_SECRET;
export const API_URL = 'https://tracker.baato.io/trackme/api';

/** Baato map style for the live map. */
export const BAATO_STYLE_URL = `https://api.baato.io/api/v1/styles/breeze?key=${BAATO_ACCESS_TOKEN}`;
export const baatoConfigured = BAATO_ACCESS_TOKEN.length > 0 && !BAATO_ACCESS_TOKEN.startsWith('your_');

/**
 * The developer screen (a control for every SDK call). On in development builds; set false to see the showcase
 * alone, like a customer would.
 */
export const DEV_TOOLS = __DEV__;
