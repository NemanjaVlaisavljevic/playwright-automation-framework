import { useState } from "react";
import { RouterProvider, type RouterProviderProps } from "react-router-dom";
import { createQueryClient } from "./query-client";
import { Providers } from "./providers";
import { router as browserRouter } from "./router";

interface AppProps {
  router?: RouterProviderProps["router"];
}

export function App({ router = browserRouter }: AppProps) {
  const [queryClient] = useState(createQueryClient);

  return (
    <Providers queryClient={queryClient}>
      <RouterProvider router={router} />
    </Providers>
  );
}
