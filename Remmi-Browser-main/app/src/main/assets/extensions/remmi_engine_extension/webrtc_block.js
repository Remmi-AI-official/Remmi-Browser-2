// Remmi Content Layer: WebRTC & Media Device Leak Prevention
// Canvas and Hardware Concurrency are handled natively by GeckoView C++ engine (privacy.resistFingerprinting & dom.maxHardwareConcurrency)
(function() {
  'use strict';
  try {
    if (typeof window !== 'undefined') {
      if (window.RTCPeerConnection) {
        window.RTCPeerConnection = function() {
          throw new Error('WebRTC disabled by Remmi Security Policy');
        };
      }
      if (window.webkitRTCPeerConnection) {
        window.webkitRTCPeerConnection = function() {
          throw new Error('WebRTC disabled by Remmi Security Policy');
        };
      }
      if (window.mozRTCPeerConnection) {
        window.mozRTCPeerConnection = function() {
          throw new Error('WebRTC disabled by Remmi Security Policy');
        };
      }
      if (window.RTCDataChannel) {
        window.RTCDataChannel = function() {
          throw new Error('RTCDataChannel disabled by Remmi Security Policy');
        };
      }
    }
  } catch (_e) {}
})();
