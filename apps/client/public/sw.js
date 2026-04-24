// Minimal service worker for push notifications only.
// No caching, no offline — just push + notification click.

self.addEventListener('push', (event) => {
  let data = {}
  if (event.data) {
    try {
      data = event.data.json()
    } catch {
      // Fallback for plain-text payloads (e.g. DevTools test push)
      data = { title: 'Coday', body: event.data.text() }
    }
  }
  event.waitUntil(
    self.registration.showNotification(data.title || 'Coday', {
      body: data.body || '',
      icon: data.icon || '/CODAY-Logo.png',
      badge: '/CODAY-Logo.png',
      data: { url: data.url || '/' },
    })
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const targetPath = event.notification.data?.url || '/'
  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windowClients) => {
      // 1. Try to find an existing window already on the target URL
      for (const client of windowClients) {
        if (new URL(client.url).pathname === targetPath && 'focus' in client) {
          return client.focus()
        }
      }
      // 2. Otherwise navigate any existing app window to the target
      if (windowClients.length > 0) {
        const client = windowClients[0]
        if ('navigate' in client) {
          return client.navigate(targetPath).then((c) => c?.focus())
        }
      }
      // 3. Last resort: open a new window
      return clients.openWindow(targetPath)
    })
  )
})
