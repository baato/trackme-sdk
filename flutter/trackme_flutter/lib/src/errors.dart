/// Every way a TrackMe call can fail. [message] is always safe to log or show; for [TrackMeErrorType.validation]
/// from the server it's the server's own text, e.g. "duration_min: Input should be greater than or equal to 5".
class TrackMeException implements Exception {
  const TrackMeException(this.type, this.message, {this.statusCode, this.canAskAgain});

  factory TrackMeException.fromJson(Map<String, dynamic> j) => TrackMeException(
        _type(j['type'] as String?),
        j['message'] as String? ?? 'Unknown error',
        statusCode: (j['status_code'] as num?)?.toInt(),
        canAskAgain: j['can_ask_again'] as bool?,
      );

  final TrackMeErrorType type;
  final String message;

  /// HTTP status for [TrackMeErrorType.server].
  final int? statusCode;

  /// For [TrackMeErrorType.locationPermissionDenied]: false = only the Settings app can fix it.
  final bool? canAskAgain;

  @override
  String toString() => 'TrackMeException(${type.name}): $message';
}

enum TrackMeErrorType {
  /// Called before `TrackMe.initialize` or after `TrackMe.dispose`.
  notInitialized,
  /// 401, and the token provider couldn't supply a working token.
  unauthorized,
  /// 403, e.g. a member trying an owner-only action.
  forbidden,
  /// 404, e.g. an unknown trip id or join code.
  notFound,
  /// 409, e.g. deleting a trip that is still live.
  conflict,
  /// Invalid input: a 400/422 from the server, or caught before sending.
  validation,
  /// Offline, DNS failure, timeout.
  network,
  /// 5xx.
  server,
  locationPermissionDenied,
  locationServicesDisabled,
  backgroundNotAllowed,
  unknown,
}

TrackMeErrorType _type(String? t) => switch (t) {
      'NotInitialized' => TrackMeErrorType.notInitialized,
      'Unauthorized' => TrackMeErrorType.unauthorized,
      'Forbidden' => TrackMeErrorType.forbidden,
      'NotFound' => TrackMeErrorType.notFound,
      'Conflict' => TrackMeErrorType.conflict,
      'Validation' => TrackMeErrorType.validation,
      'Network' => TrackMeErrorType.network,
      'Server' => TrackMeErrorType.server,
      'LocationPermissionDenied' => TrackMeErrorType.locationPermissionDenied,
      'LocationServicesDisabled' => TrackMeErrorType.locationServicesDisabled,
      'BackgroundNotAllowed' => TrackMeErrorType.backgroundNotAllowed,
      _ => TrackMeErrorType.unknown,
    };
