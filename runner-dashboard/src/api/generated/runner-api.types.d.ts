
  export namespace Schemas {
    // <Schemas>
  export type CreateRunRequest = ({ environment: ("PUBLIC" | "LOCAL"), suite: ("SMOKE" | "API" | "UI" | "JOURNEY" | "REGRESSION" | "FIXTURE") } & Record<string, unknown>)
export type RunResponse = ({ runId: string, environment: ("PUBLIC" | "LOCAL"), suite: ("SMOKE" | "API" | "UI" | "JOURNEY" | "REGRESSION" | "FIXTURE"), status: ("QUEUED" | "STARTING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "TIMED_OUT" | "ERROR"), requestedAt: string, startedAt?: string, finishedAt?: string, exitCode?: number, detail?: string, processLogUrl: string } & Record<string, unknown>)
export type ProblemDetail = ({ type?: string, title: string, status: number, detail: string, instance: string, properties?: Record<string, unknown> } & Record<string, unknown>)
export type ArtifactSummaryResponse = ({ artifactId: string, testId: string, testDisplayName: string, stepId?: string, type: ("SCREENSHOT" | "TRACE" | "VIDEO"), mediaType: string, sizeBytes: number, createdAt: string, downloadUrl: string } & Record<string, unknown>)
export type EnvironmentCapabilities = ({ name: ("PUBLIC" | "LOCAL"), suites: Array<("SMOKE" | "API" | "UI" | "JOURNEY" | "REGRESSION" | "FIXTURE")> } & Record<string, unknown>)
export type CapabilitiesResponse = ({ apiVersion: string, eventSchemaVersion: string, environments: Array<EnvironmentCapabilities> } & Record<string, unknown>)

    // </Schemas>
    }

  export namespace Endpoints {
  // <Endpoints>

  export type get_ListRuns = {
      method: "GET",
      path: "/api/v1/runs",
      requestFormat: "json",
      responseFormat: "json",
      parameters: never,
      responses: {200: Array<Schemas.RunResponse>,
500: Schemas.ProblemDetail,
},

    }
export type post_CreateRun = {
      method: "POST",
      path: "/api/v1/runs",
      requestFormat: "json",
      responseFormat: "json",
      parameters: {




        body:  Schemas.CreateRunRequest,
          }
      responses: {202: Schemas.RunResponse,
400: Schemas.ProblemDetail,
500: Schemas.ProblemDetail,
503: Schemas.ProblemDetail,
},

    }
export type post_CancelRun = {
      method: "POST",
      path: "/api/v1/runs/{runId}/cancel",
      requestFormat: "json",
      responseFormat: "json",
      parameters: {

        path:  { runId: string },



          }
      responses: {200: Schemas.RunResponse,
404: Schemas.ProblemDetail,
500: Schemas.ProblemDetail,
503: Schemas.ProblemDetail,
},

    }
export type get_GetRun = {
      method: "GET",
      path: "/api/v1/runs/{runId}",
      requestFormat: "json",
      responseFormat: "json",
      parameters: {

        path:  { runId: string },



          }
      responses: {200: Schemas.RunResponse,
404: Schemas.ProblemDetail,
500: Schemas.ProblemDetail,
},

    }
export type get_DownloadRunLog = {
      method: "GET",
      path: "/api/v1/runs/{runId}/log",
      requestFormat: "json",
      responseFormat: "json",
      parameters: {

        path:  { runId: string },



          }
      responses: {200: string,
404: Schemas.ProblemDetail,
500: Schemas.ProblemDetail,
},

    }
export type get_ListRunArtifacts = {
      method: "GET",
      path: "/api/v1/runs/{runId}/artifacts",
      requestFormat: "json",
      responseFormat: "json",
      parameters: {
            query?:  Partial<{ testId: string }>,
        path:  { runId: string },



          }
      responses: {200: Array<Schemas.ArtifactSummaryResponse>,
404: Schemas.ProblemDetail,
500: Schemas.ProblemDetail,
},

    }
export type get_DownloadRunArtifact = {
      method: "GET",
      path: "/api/v1/runs/{runId}/artifacts/{artifactId}",
      requestFormat: "json",
      responseFormat: "json",
      parameters: {

        path:  { runId: string, artifactId: string },



          }
      responses: {200: unknown,
404: Schemas.ProblemDetail,
500: Schemas.ProblemDetail,
},

    }
export type get_GetRunnerCapabilities = {
      method: "GET",
      path: "/api/v1/capabilities",
      requestFormat: "json",
      responseFormat: "json",
      parameters: never,
      responses: {200: Schemas.CapabilitiesResponse,
500: Schemas.ProblemDetail,
},

    }

  // </Endpoints>
  }


     // <EndpointByMethod>
     export type EndpointByMethod = {
     get: {
           "/api/v1/runs": Endpoints.get_ListRuns,
"/api/v1/runs/{runId}": Endpoints.get_GetRun,
"/api/v1/runs/{runId}/log": Endpoints.get_DownloadRunLog,
"/api/v1/runs/{runId}/artifacts": Endpoints.get_ListRunArtifacts,
"/api/v1/runs/{runId}/artifacts/{artifactId}": Endpoints.get_DownloadRunArtifact,
"/api/v1/capabilities": Endpoints.get_GetRunnerCapabilities
         },
post: {
           "/api/v1/runs": Endpoints.post_CreateRun,
"/api/v1/runs/{runId}/cancel": Endpoints.post_CancelRun
         }
     }

     // </EndpointByMethod>


    // <EndpointByMethod.Shorthands>
    export type GetEndpoints = EndpointByMethod["get"]
export type PostEndpoints = EndpointByMethod["post"]
    // </EndpointByMethod.Shorthands>
