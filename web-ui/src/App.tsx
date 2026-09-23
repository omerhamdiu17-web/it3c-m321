import { useEffect, useState } from 'react'
import { Chat } from './Chat'

/** Was das Gateway unter /api/me liefert (siehe CurrentUser.java). */
type CurrentUser = {
  username: string
  displayName: string
}

/** Ein Raum, wie ihn das Gateway unter /api/rooms liefert (siehe Room.java). */
type Room = {
  id: string
  name: string
}

/**
 * Der Rahmen der Oberfläche: zeigt, wer angemeldet ist, die Raumliste und
 * darunter den Chat des gewählten Raums.
 *
 * Die App weiss nichts von Keycloak. Sie ruft nur /api/me und /api/rooms
 * auf; das Session-Cookie schickt der Browser von selbst mit. Wer nicht
 * angemeldet ist, kommt gar nicht bis hierher, weil das Gateway vorher zu
 * Keycloak weiterleitet.
 */
export function App() {
  const [user, setUser] = useState<CurrentUser | null>(null)
  const [rooms, setRooms] = useState<Room[]>([])
  const [selectedRoom, setSelectedRoom] = useState<Room | null>(null)
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

  useEffect(() => {
    // Die Räume kommen aus der Datenbank (über Gateway und chat-service).
    // Der erste Raum der Liste ist die Lobby; die ist beim Start offen.
    async function loadRooms() {
      const response = await fetch('/api/rooms')
      if (!response.ok) {
        setError('Räume konnten nicht geladen werden (HTTP ' + response.status + ')')
        return
      }
      const loadedRooms: Room[] = await response.json()
      setRooms(loadedRooms)
      if (loadedRooms.length > 0) {
        setSelectedRoom(loadedRooms[0])
      }
    }
    loadRooms()
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

      <nav aria-label="Räume">
        {rooms.map((room) => (
          <button
            key={room.id}
            type="button"
            onClick={() => setSelectedRoom(room)}
            aria-pressed={selectedRoom !== null && selectedRoom.id === room.id}
          >
            {room.name}
          </button>
        ))}
      </nav>

      {/*
        key={...}: wechselt der Raum, baut React den Chat komplett neu auf.
        Damit schliesst sich die alte WebSocket-Verbindung, eine neue für den
        neuen Raum geht auf, und der Verlauf wird frisch geladen.
      */}
      {selectedRoom !== null && <Chat key={selectedRoom.id} roomId={selectedRoom.id} roomName={selectedRoom.name} />}
    </main>
  )
}
