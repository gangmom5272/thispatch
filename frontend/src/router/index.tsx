import { createBrowserRouter } from "react-router"
import App from "../App"
import GameListPage from "../pages/GameList/GameListPage"
import NotFound from "../pages/NotFound"

export const router = createBrowserRouter([
  {
    path: "/",
    element: <App />,
  },
  {
    path: "/list",
    element: <GameListPage />,
  },
  {
    path: "*",
    element: <NotFound />,
  },
])
