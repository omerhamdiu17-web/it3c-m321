import { useEffect, useState } from 'react'
import { Chat } from './Chat'

/** Was das Gateway unter /api/me liefert (siehe CurrentUser.java). */
type CurrentUser = {
  username: string
  displayName: string
}

/**
 * Der Rahmen der Oberfläche: zeigt, wer angemeldet ist, und darunter den Chat.
 *
 * Die App weiss nichts von Keycloak. Sie ruft nur /api/me auf; das
 * Session-Cookie schickt der Browser von selbst mit. Wer nicht angemeldet
 * ist, kommt gar nicht bis hierher, weil das Gateway vorher zu Keycloak
 * weiterleitet.
 */
export function App() {
  const [user, setUser] = useState<CurrentUser | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    async function loadCurrentUser() {
      const response = await fetch('/api/me')
      if (!response.ok) {
        setError('Benutzer konnte nicht geladen werden (HTTP ' + response.status + ')')
        return
      }
      const currentUser: CurrentUser = await response.json()
      setUser(currentUser)
    }
    loadCurrentUser()
  }, [])

  if (error !== null) {
    return <p>{error}</p>
  }

  if (user === null) {
    return <p>Lade Benutzer …</p>
  }

  return (
    <main>
      <h1>M321 Chat-App</h1>
      <p>
        Angemeldet als <strong>{user.displayName}</strong> ({user.username})
      </p>
      {/* Ein einfacher Link: das Gateway beendet die Sitzung und meldet auch bei Keycloak ab. */}
      <a href="/logout">Abmelden</a>
      {/* Der Chat wird erst gezeigt, wenn der Benutzer geladen ist. */}
      <Chat />
    </main>
  )
}
