/**
 * Coday Service Worker
 * Minimal service worker required for PWA installability.
 * No caching or offline support — just enough for Chrome/Safari to recognize the PWA.
 */

self.addEventListener('install', () => {
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})
