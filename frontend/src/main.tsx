import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import { installUmami } from './analytics.ts'
import App from './App.tsx'

installUmami(import.meta.env.VITE_UMAMI_SRC, import.meta.env.VITE_UMAMI_WEBSITE_ID)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
