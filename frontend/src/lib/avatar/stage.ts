import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { profile } from "./profile";

type Phase = "ready" | "preparing" | "playing" | "stopped" | "ended";
function disposeModel(root: THREE.Object3D) {
  const textures = new Set<THREE.Texture>();
  root.traverse((o) => {
    if (!(o instanceof THREE.Mesh)) return;
    o.geometry.dispose();
    for (const material of Array.isArray(o.material)
      ? o.material
      : [o.material]) {
      for (const value of Object.values(material))
        if (value instanceof THREE.Texture) textures.add(value);
      material.dispose();
    }
    if (o instanceof THREE.SkinnedMesh) o.skeleton.dispose();
  });
  for (const texture of textures) {
    texture.dispose();
    if (texture.image instanceof ImageBitmap) texture.image.close();
  }
}
export async function createStage(
  host: HTMLDivElement,
  signal: AbortSignal,
  onPhase: (phase: Phase) => void,
  onError: (message: string) => void,
) {
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.1;
  renderer.domElement.setAttribute("aria-label", "試作ライバルの3D表示");
  renderer.domElement.setAttribute("role", "img");
  host.append(renderer.domElement);
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(30, 1, 0.01, 100);
  scene.add(new THREE.HemisphereLight(0xfff6e9, 0x657875, 2.2));
  const key = new THREE.DirectionalLight(0xffeddb, 3.1);
  key.position.set(2, 3, 4);
  scene.add(key);
  const fill = new THREE.DirectionalLight(0xdceaff, 1.7);
  fill.position.set(-2, 2, 2);
  scene.add(fill);
  let model: THREE.Object3D | undefined;
  let frame = 0,
    disposed = false,
    epoch = 0;
  let context: AudioContext | undefined, buffer: AudioBuffer | undefined;
  let source: AudioBufferSourceNode | undefined,
    analyser: AnalyserNode | undefined;
  let samples: Float32Array<ArrayBuffer> | undefined;
  let mouth = 0;
  const meshes: THREE.Mesh[] = [];
  const morph = (name: string, value: number) => {
    for (const mesh of meshes) {
      const i = mesh.morphTargetDictionary?.[name];
      if (i !== undefined && mesh.morphTargetInfluences)
        mesh.morphTargetInfluences[i] = value;
    }
  };
  const closeMouth = () => {
    mouth = 0;
    morph(profile.mouth, 0);
    host.dataset.mouth = "0";
  };
  const stop = (notify = true) => {
    epoch++;
    if (source) {
      source.onended = null;
      source.stop();
      source.disconnect();
      source = undefined;
    }
    analyser?.disconnect();
    analyser = undefined;
    closeMouth();
    if (notify && !disposed) onPhase("stopped");
  };
  const resize = () => {
    const width = host.clientWidth,
      height = host.clientHeight;
    if (!width || !height) return;
    renderer.setSize(width, height);
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
  };
  const observer = new ResizeObserver(resize);
  observer.observe(host);
  resize();
  const visibility = () => {
    if (document.hidden) stop();
  };
  const contextLost = (event: Event) => {
    event.preventDefault();
    stop();
    onError("3D表示が中断されました。ページを再読み込みしてください。");
  };
  renderer.domElement.addEventListener("webglcontextlost", contextLost);
  document.addEventListener("visibilitychange", visibility);
  const dispose = () => {
    if (disposed) return;
    disposed = true;
    stop(false);
    cancelAnimationFrame(frame);
    observer.disconnect();
    document.removeEventListener("visibilitychange", visibility);
    renderer.domElement.removeEventListener("webglcontextlost", contextLost);
    void context?.close().catch(() => {});
    if (model) disposeModel(model);
    renderer.dispose();
    renderer.forceContextLoss();
    renderer.domElement.remove();
  };
  signal.addEventListener("abort", dispose, { once: true });
  try {
    const fetchBytes = async (url: string) => {
      const response = await fetch(url, { signal, cache: "no-store" });
      if (!response.ok) throw new Error("Asset request failed");
      return response.arrayBuffer();
    };
    const [modelBytes, audioBytes] = await Promise.all([
      fetchBytes(profile.modelUrl),
      fetchBytes(profile.audioUrl),
    ]);
    const gltf = await new GLTFLoader().parseAsync(modelBytes, "");
    if (disposed) {
      disposeModel(gltf.scene);
      throw new DOMException("Aborted", "AbortError");
    }
    model = gltf.scene;
    model.traverse((o) => {
      if (o instanceof THREE.Mesh) meshes.push(o);
    });
    for (const target of [profile.mouth, ...profile.blink]) {
      if (!meshes.some((m) => m.morphTargetDictionary?.[target] !== undefined))
        throw new Error("Missing face binding");
    }
    scene.add(model);
    model.updateMatrixWorld(true);
    for (const pose of profile.armPose) {
      const arm = model.getObjectByName(pose.name);
      if (!arm?.parent) continue;
      const parentRotation = arm.parent.getWorldQuaternion(
        new THREE.Quaternion(),
      );
      const world = arm.getWorldQuaternion(new THREE.Quaternion());
      const turn = new THREE.Quaternion().setFromAxisAngle(
        new THREE.Vector3(0, 0, 1),
        pose.angle,
      );
      arm.quaternion.copy(
        parentRotation.invert().multiply(turn).multiply(world),
      );
      model.updateMatrixWorld(true);
    }
    const head = model.getObjectByName(profile.head),
      neck = model.getObjectByName(profile.neck);
    if (!head || !neck) throw new Error("Missing head binding");
    const headBase = head.quaternion.clone(),
      neckBase = neck.quaternion.clone();
    const headPosition = head.getWorldPosition(new THREE.Vector3());
    const bounds = new THREE.Box3().setFromObject(model);
    const height = bounds.max.y - bounds.min.y;
    const aim = new THREE.Vector3(
      headPosition.x,
      headPosition.y - height * 0.06,
      headPosition.z,
    );
    camera.position.set(
      aim.x,
      aim.y + height * 0.025,
      aim.z + height * profile.cameraDistance,
    );
    camera.lookAt(aim);
    for (const name of profile.smile) morph(name, 0.12);
    const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");
    const rotation = new THREE.Quaternion();
    const euler = new THREE.Euler();
    let previous = performance.now();
    const start = previous;
    const animate = (now: number) => {
      if (disposed) return;
      const dt = Math.min((now - previous) / 1000, 0.1);
      previous = now;
      const t = (now - start) / 1000;
      let desired = 0;
      if (source && analyser && samples) {
        analyser.getFloatTimeDomainData(samples);
        let energy = 0;
        for (const sample of samples) energy += sample * sample;
        const rms = Math.sqrt(energy / samples.length);
        desired = rms < 0.012 ? 0 : Math.min(0.72, (rms - 0.012) * 7);
      }
      mouth = desired === 0 ? 0 : THREE.MathUtils.damp(mouth, desired, 24, dt);
      morph(profile.mouth, mouth);
      host.dataset.mouth = mouth.toFixed(3);
      const blinkTime = t % 4.3;
      const blink = reducedMotion.matches
        ? 0
        : Math.max(0, 1 - Math.abs(blinkTime - 3.8) / 0.13);
      for (const name of profile.blink) morph(name, blink);
      host.dataset.blink = blink.toFixed(3);
      if (reducedMotion.matches) {
        head.quaternion.copy(headBase);
        neck.quaternion.copy(neckBase);
      } else {
        head.quaternion
          .copy(headBase)
          .multiply(
            rotation.setFromEuler(
              euler.set(
                Math.sin(t * 1.1) * 0.012,
                Math.sin(t * 0.65) * 0.025,
                Math.sin(t * 0.8) * 0.012,
              ),
            ),
          );
        neck.quaternion
          .copy(neckBase)
          .multiply(
            rotation.setFromEuler(euler.set(0, Math.sin(t * 0.45) * 0.008, 0)),
          );
      }
      renderer.render(scene, camera);
      frame = requestAnimationFrame(animate);
    };
    frame = requestAnimationFrame(animate);
    onPhase("ready");
    return {
      stop,
      dispose,
      async play() {
        stop(false);
        const ticket = epoch;
        onPhase("preparing");
        try {
          context ??= new AudioContext();
          await context.resume();
          buffer ??= await context.decodeAudioData(audioBytes.slice(0));
          if (disposed || ticket !== epoch) return;
          analyser = context.createAnalyser();
          analyser.fftSize = 1024;
          samples = new Float32Array(analyser.fftSize);
          source = context.createBufferSource();
          source.buffer = buffer;
          source.connect(analyser);
          analyser.connect(context.destination);
          source.onended = () => {
            if (ticket === epoch && !disposed) {
              stop(false);
              onPhase("ended");
            }
          };
          source.start();
          onPhase("playing");
        } catch {
          if (disposed || ticket !== epoch) return;
          stop(false);
          onError(
            "音声を再生できませんでした。ページを再読み込みしてお試しください。",
          );
        }
      },
    };
  } catch (error) {
    dispose();
    throw error;
  }
}
