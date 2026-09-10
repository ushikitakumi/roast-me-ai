import { z } from "zod";
export const goalForm = z.object({
  title: z.string().trim().min(1).max(100),
  successCriteria: z.string().trim().min(1).max(1000),
  description: z.string().trim().max(1000),
  deadline: z.string().nullable(),
  requestVideo: z.boolean(),
});
export const progressForm = z.object({
  body: z.string().trim().min(1).max(1000),
  progressCategory: z.enum(["DONE", "PARTIAL", "NOT_DONE", "REST", "UNWELL"]),
  requestVideo: z.boolean(),
});
