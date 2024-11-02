Shader "Mixed Reality Toolkit/LightCapture IBL URP"
{
    Properties
    {
        _MainTex("Texture", 2D) = "white" {}
        [Toggle(USE_NORMALMAP)]
        _UseNormals("Use NormalMap", float) = 1
        [NoScaleOffset]
        _BumpMap("Normal", 2D) = "bump" {}
        [NoScaleOffset]
        _RoughMap("Roughness", 2D) = "white" {}
        _Smoothness("Smoothness", Range(0,1)) = 1
        [Toggle(USE_REFLECTION)]
        _UseReflections("Use Reflection", float) = 1
    }

    SubShader
    {
        Tags 
        { 
            "RenderType" = "Opaque"
            "RenderPipeline" = "UniversalPipeline"
            "Queue" = "Geometry"
        }
        LOD 100

        Pass
        {
            Name "ForwardLit"
            Tags { "LightMode" = "UniversalForward" }

            HLSLPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #pragma multi_compile_instancing
            #pragma shader_feature USE_NORMALMAP
            #pragma shader_feature USE_REFLECTION

            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"
            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Lighting.hlsl"
            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Input.hlsl"

            struct Attributes
            {
                float3 positionOS : POSITION;
                float2 uv : TEXCOORD0;
                float3 normalOS : NORMAL;
                #if USE_NORMALMAP
                    float4 tangentOS : TANGENT;
                #endif
                UNITY_VERTEX_INPUT_INSTANCE_ID
            };

            struct Varyings
            {
                float2 uv : TEXCOORD0;
                float4 positionCS : SV_POSITION;
                float3 positionWS : TEXCOORD1;
                #if USE_NORMALMAP
                    float3 tangentWS : TEXCOORD2;
                    float3 bitangentWS : TEXCOORD3;
                    float3 normalWS : TEXCOORD4;
                #else
                    float3 normalWS : NORMAL;
                #endif
                UNITY_VERTEX_OUTPUT_STEREO
            };

            TEXTURE2D(_MainTex);
            TEXTURE2D(_BumpMap);
            TEXTURE2D(_RoughMap);
            SAMPLER(sampler_MainTex);
            SAMPLER(sampler_BumpMap);
            SAMPLER(sampler_RoughMap);

            CBUFFER_START(UnityPerMaterial)
                float4 _MainTex_ST;
                float _Smoothness;
            CBUFFER_END

            Varyings vert(Attributes input)
            {
                Varyings output = (Varyings)0;
                UNITY_SETUP_INSTANCE_ID(input);
                UNITY_INITIALIZE_VERTEX_OUTPUT_STEREO(output);

                VertexPositionInputs vertexInput = GetVertexPositionInputs(input.positionOS);
                output.positionCS = vertexInput.positionCS;
                output.positionWS = vertexInput.positionWS;
                output.uv = TRANSFORM_TEX(input.uv, _MainTex);

                #if USE_NORMALMAP
                    VertexNormalInputs normalInput = GetVertexNormalInputs(input.normalOS, input.tangentOS);
                    output.tangentWS = normalInput.tangentWS;
                    output.bitangentWS = normalInput.bitangentWS;
                    output.normalWS = normalInput.normalWS;
                #else
                    output.normalWS = TransformObjectToWorldNormal(input.normalOS);
                #endif

                return output;
            }

            half4 frag(Varyings input) : SV_Target
            {
                // Sample textures
                half4 albedo = SAMPLE_TEXTURE2D(_MainTex, sampler_MainTex, input.uv);
                half4 rough = SAMPLE_TEXTURE2D(_RoughMap, sampler_RoughMap, input.uv);

                // Calculate normal
                half3 normalWS;
                #if USE_NORMALMAP
                    half3 normalTS = UnpackNormal(SAMPLE_TEXTURE2D(_BumpMap, sampler_BumpMap, input.uv));
                    float3x3 tangentToWorld = float3x3(input.tangentWS, input.bitangentWS, input.normalWS);
                    normalWS = normalize(mul(normalTS, tangentToWorld));
                #else
                    normalWS = normalize(input.normalWS);
                #endif

                // Setup modifier values
                float smooth = max(0.0001, _Smoothness) * rough.a;
                float iSmooth = 1 - smooth;
                float metal = rough.r * _Smoothness;
                float occlusion = rough.g;

                // Get lighting information
                Light mainLight = GetMainLight();
                float3 viewDirWS = normalize(GetWorldSpaceViewDir(input.positionWS));
                float3 halfVector = normalize(mainLight.direction + viewDirWS);
                float3 reflectVector = reflect(-viewDirWS, normalWS);

                // Calculate specular
                float specularHigh = pow(saturate(dot(halfVector, normalWS)), smooth * 40);
                half3 specColor = mainLight.color * lerp(1, albedo.rgb, 1 - metal);

                #if USE_REFLECTION
                    half3 reflection = GlossyEnvironmentReflection(reflectVector, iSmooth * 8, occlusion);
                    half3 specular = occlusion * metal * (specColor * specularHigh + reflection);
                    albedo.rgb *= iSmooth;
                #else
                    half3 specular = occlusion * metal * (specColor * specularHigh);
                #endif

                // Calculate lighting
                half3 ambient = SampleSH(normalWS);
                half nl = saturate(dot(normalWS, mainLight.direction));

                return half4((ambient + nl * mainLight.color) * albedo.rgb + specular, 1);
            }
            ENDHLSL
        }
    }
    FallBack "Universal Render Pipeline/Lit"
}