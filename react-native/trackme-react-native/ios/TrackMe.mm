// Turbo Module shim: React Native's codegen needs Objective-C++ here. The work is in TrackMeImpl.swift, a
// pass-through to TrackMeBridge in the TrackMe SDK (docs/07-ports.md).
#import <React/RCTEventEmitter.h>
#import <RNTrackMeSpec/RNTrackMeSpec.h>
#import "TrackMeReactNative-Swift.h"

@interface TrackMe : RCTEventEmitter <NativeTrackMeSpec>
@end

@implementation TrackMe {
  TrackMeImpl *_impl;
}

RCT_EXPORT_MODULE(TrackMe)

- (instancetype)init
{
  if (self = [super init]) {
    __weak TrackMe *weakSelf = self;
    _impl = [[TrackMeImpl alloc] initWithEmit:^(NSString *name, NSDictionary *body) {
      [weakSelf sendEventWithName:name body:body];
    }];
  }
  return self;
}

+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

- (NSArray<NSString *> *)supportedEvents
{
  return @[ @"TrackMeEvent", @"TrackMeRefreshToken" ];
}

// RCTEventEmitter calls these when the first JS listener arrives and the last one leaves.
- (void)startObserving
{
  [_impl startObserving];
}

- (void)stopObserving
{
  [_impl stopObserving];
}

- (void)start:(NSString *)configJson
    hasTokenProvider:(BOOL)hasTokenProvider
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject
{
  resolve([_impl start:configJson hasTokenProvider:hasTokenProvider]);
}

- (void)call:(NSString *)method
    argsJson:(NSString *)argsJson
     resolve:(RCTPromiseResolveBlock)resolve
      reject:(RCTPromiseRejectBlock)reject
{
  [_impl call:method argsJson:argsJson completion:^(NSString *envelope) {
    resolve(envelope);
  }];
}

- (void)provideToken:(double)requestId token:(NSString *_Nullable)token
{
  [_impl provideToken:(NSInteger)requestId token:token];
}

- (void)invalidate
{
  [_impl invalidate];
  if ([RCTEventEmitter instancesRespondToSelector:@selector(invalidate)]) {
    [super invalidate];
  }
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeTrackMeSpecJSI>(params);
}

@end
