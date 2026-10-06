// Model-specific bindings live here so a future self-made model can replace this sample.
export const profile = {
  modelUrl: "/api/avatar-preview/model",
  audioUrl: "/api/avatar-preview/audio",
  mouth: "jawOpen",
  armPose: [
    { name: "LeftArm", angle: -0.48 },
    { name: "RightArm", angle: 0.48 },
  ],
  cameraDistance: 0.8,
  blink: ["eyeBlinkLeft", "eyeBlinkRight"],
  head: "Head",
  neck: "Neck",
  smile: ["mouthSmileLeft", "mouthSmileRight"],
} as const;
