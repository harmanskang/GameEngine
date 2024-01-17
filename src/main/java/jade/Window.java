package jade;

import observers.EventSystem;
import observers.Observer;
import observers.events.Event;
import org.joml.Vector4f;
import org.lwjgl.Version;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.opengl.GL;
import physics2d.Physics2D;
import renderer.*;
import scenes.LevelEditorSceneIntializer;
import scenes.LevelSceneIntializer;
import scenes.Scene;
import scenes.SceneIntializer;
import util.AssetPool;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class Window implements Observer {
    private int width, height;
    private String title;
    private long glfwWindow;
    private ImGuiLayer imguiLayer;
    private FrameBuffer framebuffer;
    private PickingTexture pickingTexture;
    private boolean runtimePlaying = false;
    private static Window window = null;

    private long audioContext;
    private long audioDevice;

    private static Scene currentScene;


    private Window(){
        this.width = 1920;
        this.height = 1080;
        this.title = "Mario";
        EventSystem.addObserver(this);

    }

    public static void changeScene(SceneIntializer sceneIntializer){
        if (currentScene != null){
            currentScene.destroy();
        }

        getImguiLayer().getPropertiesWindow().setActiveGameObject(null);
        currentScene = new Scene(sceneIntializer);
        currentScene.load();
        currentScene.init();
        currentScene.start();
    }

    public static Window get(){
        if (Window.window == null){
            Window.window = new Window();
        }
        return Window.window;
    }

    public static Physics2D getPhysics(){
        return currentScene.getPhysics();
    }

    public static Scene getScene(){
        return get().currentScene;
    }

    public void run(){
        System.out.println("Hello LWJGL" + Version.getVersion() + "!");

        init();
        loop();

        //destroy audio context
        alcDestroyContext(audioContext);
        alcCloseDevice(audioDevice);

        //free the memory
        glfwFreeCallbacks(glfwWindow);
        glfwDestroyWindow(glfwWindow);

        //terminate GLFW and free the error callback
        glfwTerminate();;
        glfwSetErrorCallback(null).free();
    }

    public void init(){
        //setup an error callback
        GLFWErrorCallback.createPrint(System.err).set();

        //intialize GLFW
        if (!glfwInit()){
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        //configure GLFW
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_MAXIMIZED, GLFW_TRUE);

        //create the window
        glfwWindow = glfwCreateWindow(this.width,this.height, this.title, NULL, NULL);
        if (glfwWindow == NULL){
            throw new IllegalStateException("Failed to create the GLFW window.");
        }

        glfwSetCursorPosCallback(glfwWindow, MouseListener::mousePosCallback);
        glfwSetMouseButtonCallback(glfwWindow, MouseListener::mouseButtonCallback);
        glfwSetScrollCallback(glfwWindow, MouseListener::mouseScrollCallback);
        glfwSetKeyCallback(glfwWindow, KeyListener::keyCallback);
        glfwSetWindowSizeCallback(glfwWindow, (w, newWidth, newHeight) -> {
            Window.setWidth(newWidth);
            Window.setHeight(newHeight);
        });

        //Make the OpenGL context current
        glfwMakeContextCurrent(glfwWindow);

        //Enable v-sync
        glfwSwapInterval(1);

        //make window visible
        glfwShowWindow(glfwWindow);

        //initialize the audio device
        String defaultDeviceName = alcGetString(0, ALC_DEFAULT_DEVICE_SPECIFIER);
        audioDevice = alcOpenDevice(defaultDeviceName);

        int[] attributes = {0};
        audioContext = alcCreateContext(audioDevice, attributes);
        alcMakeContextCurrent(audioContext);

        ALCCapabilities alcCapabilities = ALC.createCapabilities(audioDevice);
        ALCapabilities alCapabilities = AL.createCapabilities(alcCapabilities);

        if (!alCapabilities.OpenAL10){
            assert false : "Audio library not supported.";
        }

        // This line is critical for LWJGL's interoperation with GLFW's
        // OpenGL context, or any context that is managed externally.
        // LWJGL detects the context that is current in the current thread,
        // creates the GLCapabilities instance and makes the OpenGL
        // bindings available for use.
        GL.createCapabilities();

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        this.framebuffer = new FrameBuffer(1920, 1080);
        this.pickingTexture = new PickingTexture(1920, 1080);
        glViewport(0, 0, 1920, 1080);

        this.imguiLayer = new ImGuiLayer(glfwWindow, pickingTexture);
        this.imguiLayer.initImGui();

        Window.changeScene(new LevelEditorSceneIntializer());
    }

    public void loop(){
        float beginTime = (float)glfwGetTime();
        float endTime;
        float dt = -1.0f;

        Shader defaultShader = AssetPool.getShader("assets/shaders/default.glsl");
        Shader pickingShader = AssetPool.getShader("assets/shaders/pickingShader.glsl");

        while(!glfwWindowShouldClose(glfwWindow)){
            //poll events
            glfwPollEvents();

            //render pass 1. render to picking texture
            glDisable(GL_BLEND);
            pickingTexture.enableWriting();

            glViewport(0, 0, 1920, 1080);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            Renderer.bindShader(pickingShader);
            currentScene.render();

            pickingTexture.disableWriting();
            glEnable(GL_BLEND);

            //render pass 2. render to actual game
            DebugDraw.beginFrame();

            this.framebuffer.bind();
            Vector4f clearColor = currentScene.camera().clearColor;
            glClearColor(clearColor.x, clearColor.y, clearColor.z, clearColor.w);
            glClear(GL_COLOR_BUFFER_BIT);

            if (dt >= 0){
                Renderer.bindShader(defaultShader);
                if (runtimePlaying) {
                    currentScene.update(dt);
                }else {
                    currentScene.editorUpdate(dt);
                }
                currentScene.render();
                DebugDraw.draw();
            }
            this.framebuffer.unbind();

            this.imguiLayer.update(dt, currentScene);

            KeyListener.endFrame();
            MouseListener.endFrame();
            glfwSwapBuffers(glfwWindow);

            endTime = (float)glfwGetTime();
            dt = endTime - beginTime;
            beginTime = endTime;
        }
    }

    public static int getWidth(){
        return 1920;
        //return get().width;
    }

    public static int getHeight(){
        return 1080;
        //return get().height;
    }

    public static void setWidth (int newWidth){
        get().width = newWidth;
    }

    public static void setHeight (int newHeight){
        get().height = newHeight;
    }

    public static FrameBuffer getFramebuffer(){
        return get().framebuffer;
    }

    public static float getTargetAspectRatio(){
        return 16.0f/9.0f;
    }

    public static ImGuiLayer getImguiLayer(){
        return get().imguiLayer;
    }

    @Override
    public void onNotify(GameObject object, Event event) {
        switch (event.type){
            case GameEngineStartPlay -> {
                this.runtimePlaying = true;
                currentScene.save();
                Window.changeScene(new LevelSceneIntializer());
            }
            case GameEngineStopPlay -> {
                this.runtimePlaying = false;
                Window.changeScene(new LevelEditorSceneIntializer());
            }
            case LoadLevel -> Window.changeScene(new LevelEditorSceneIntializer());
            case SaveLevel -> currentScene.save();
        }
    }
}
