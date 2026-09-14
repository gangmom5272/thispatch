import { createBrowserRouter } from "react-router"
import { AppShell } from "@/components/layout/AppShell"
import LoginPage from "@/pages/login/LoginPage"
import NotFound from "@/pages/NotFound"
import { PlaceholderPage } from "@/pages/PlaceholderPage"
import SignupPage from "@/pages/signup/SignupPage"

export const router = createBrowserRouter([
  {
    path: "/",
    element: <AppShell />,
    children: [
      {
        index: true,
        element: <PlaceholderPage title="홈" />,
      },
      {
        path: "games",
        element: <PlaceholderPage title="게임 목록" />,
      },
      {
        path: "games/:gameId",
        element: <PlaceholderPage title="게임 상세" />,
      },
      {
        path: "methodology",
        element: <PlaceholderPage title="방법론" />,
      },
      {
        path: "login",
        element: <LoginPage />,
      },
      {
        path: "signup",
        element: <SignupPage />,
      },
      {
        path: "*",
        element: <NotFound />,
      },
    ],
  },
])
