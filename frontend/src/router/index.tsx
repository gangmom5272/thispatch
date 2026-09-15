import { createBrowserRouter } from "react-router"
import { AppShell } from "@/components/layout/AppShell"
import LoginPage from "@/pages/login/LoginPage"
import NotFound from "@/pages/NotFound"
import { PlaceholderPage } from "@/pages/PlaceholderPage"
import SignupPage from "@/pages/signup/SignupPage"
import { paths, routeSegment } from "@/router/paths"
import GameDetailPage from "@/pages/GameDetail/GameDetailPage"
import GameListPage from "@/pages/GameList/GameListPage"

export const router = createBrowserRouter([
  {
    path: paths.home,
    element: <AppShell />,
    children: [
      {
        index: true,
        element: <PlaceholderPage title="홈" />,
      },
      {
        path: routeSegment(paths.games),
        element: <GameListPage />,
      },
      {
        path: routeSegment(paths.gameDetailPattern),
        element: <GameDetailPage />,
      },
      {
        path: routeSegment(paths.methodology),
        element: <PlaceholderPage title="방법론" />,
      },
      {
        path: routeSegment(paths.login),
        element: <LoginPage />,
      },
      {
        path: routeSegment(paths.signup),
        element: <SignupPage />,
      },
      {
        path: "*",
        element: <NotFound />,
      },
    ],
  },
])
