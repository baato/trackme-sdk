// Turbo Module spec (React Native codegen reads this file). Strings only: the native side is a pass-through to
// TrackMeBridge in the TrackMe SDK (docs/07-ports.md). Use the typed API in ./index.ts instead.
import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export interface Spec extends TurboModule {
  /** TrackMeBridge.start: runs TrackMe.initialize. Resolves with the JSON envelope. */
  start(configJson: string, hasTokenProvider: boolean): Promise<string>;
  /** TrackMeBridge.call. Resolves with the JSON envelope. */
  call(method: string, argsJson: string): Promise<string>;
  /** Answer to a `TrackMeRefreshToken` event. */
  provideToken(requestId: number, token: string | null): void;

  // Events: `TrackMeEvent` {json} and `TrackMeRefreshToken` {requestId}, via NativeEventEmitter.
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('TrackMe');
