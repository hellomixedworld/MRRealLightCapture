﻿// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in the project root for license information.

using System;
using Unity.Collections;
using UnityEngine;
using UnityEngine.UI;

namespace Microsoft.MixedReality.Toolkit.LightingTools
{
	public class CameraCaptureMediaProjection : ICameraCapture
	{
		/// <summary>Which screen are we rendering?</summary>
		private Camera           sourceCamera;
		/// <summary>Preferred resolution for taking pictures, note that resolutions are not guaranteed! Refer to CameraResolution for details.</summary>
		private CameraResolution resolution;
		/// <summary>Cache tex for storing the screen.</summary>
		private Texture2D        captureTex  = null;
		/// <summary>Is this ICameraCapture ready for capturing pictures?</summary>
		private bool             ready       = false;
		private bool isCapturing = false;
		/// <summary>For controlling which render layers get rendered for this capture.</summary>
		private int              renderMask  = ~(1 << 31);
		/// <summary>Cache for the source texture</summary>
		private RawImage sourceTexture;

		/// <summary>
		/// Is the camera completely initialized and ready to begin taking pictures?
		/// </summary>
		public bool  IsReady
		{
			get
			{
				return ready;
			}
		}
		/// <summary>
		/// Is the camera currently already busy with taking a picture?
		/// </summary>
		public bool  IsRequestingImage
		{
			get
			{
				return isCapturing;
			}
		}
		/// <summary>
		/// Field of View of the camera in degrees. This value is never ready until after 
		/// initialization, and in many cases, isn't accurate until after a picture has
		/// been taken. It's best to check this after each picture if you need it.
		/// </summary>
		public float FieldOfView
		{
			get
			{
				return 82f; // Fixed 82 degrees
			}
		}
		
		/// <param name="aSourceTexture">Which screen are we rendering?</param>
		/// <param name="aRenderMask">For controlling which render layers get rendered for this capture.</param>
		public CameraCaptureMediaProjection(RawImage aSourceTexture, int aRenderMask = ~(1 << 31))
		{
			sourceTexture = aSourceTexture;
			renderMask   = aRenderMask;
			sourceCamera = Camera.main;
		}

		/// <summary>
		/// Starts up and selects a device's camera, and finds appropriate picture settings
		/// based on the provided resolution! 
		/// </summary>
		/// <param name="preferGPUTexture">Do you prefer GPU textures, or do you prefer a NativeArray of colors? Certain optimizations may be present to take advantage of this preference.</param>
		/// <param name="resolution">Preferred resolution for taking pictures, note that resolutions are not guaranteed! Refer to CameraResolution for details.</param>
		/// <param name="onInitialized">When the camera is initialized, this callback is called! Some cameras may return immediately, others may take a while. Can be null.</param>
		public void Initialize(bool aPreferGPUTexture, CameraResolution aResolution, Action aOnInitialized)
		{
			resolution = aResolution;
            resolution.size = new Vector2Int(1024, 1024);
            ready = true;

			if (aOnInitialized != null)
			{
				aOnInitialized();
			}
		}

		/// <summary>
		/// Render the current scene to a texture.
		/// </summary>
		/// <param name="aSize">Desired size to render.</param>
		private void GrabScreen(Vector2Int aSize)
		{
			Debug.Log("Capturing new Image");
			isCapturing = true;
			if (captureTex == null || captureTex.width != aSize.x || captureTex.height != aSize.y)
			{
				if (captureTex != null)
				{
					GameObject.Destroy(captureTex);
				}
				captureTex = new Texture2D(aSize.x, aSize.y, TextureFormat.RGB24, false);
			}

			RenderTexture rt = RenderTexture.GetTemporary(aSize.x, aSize.y, 24);
			Graphics.Blit(sourceTexture.texture, rt);
			
			RenderTexture.active = rt;
			captureTex.ReadPixels(new Rect(0, 0, aSize.x, aSize.y), 0, 0, false);
			captureTex.Apply();
			RenderTexture.active = null;

			RenderTexture.ReleaseTemporary(rt);
			isCapturing = false;
		}

		/// <summary>
		/// Request an image from the camera, and provide it as an array of colors on the CPU!
		/// </summary>
		/// <param name="onImageAcquired">This is the function that will be called when the image is ready. Matrix is the transform of the device when the picture was taken, and integers are width and height of the NativeArray.</param>
		public void RequestImage(Action<NativeArray<Color24>, Matrix4x4, int, int> aOnImageAcquired)
		{
			isCapturing = true;
			Vector2Int size = resolution.size;
			GrabScreen(size);

			if (aOnImageAcquired != null)
			{
				aOnImageAcquired(captureTex.GetRawTextureData<Color24>(), sourceCamera.transform.localToWorldMatrix, size.x, size.y);
			}
			isCapturing = false;
		}

		/// <summary>
		/// Request an image from the camera, and provide it as a GPU Texture!
		/// </summary>
		/// <param name="onImageAcquired">This is the function that will be called when the image is ready. Texture is not guaranteed to be a Texture2D, could also be a WebcamTexture. Matrix is the transform of the device when the picture was taken.</param>
		public void RequestImage(Action<Texture, Matrix4x4> aOnImageAcquired)
		{
			Vector2Int size = resolution.size;
			GrabScreen(size);

			if (aOnImageAcquired != null && captureTex != null)
			{
				aOnImageAcquired(captureTex, sourceCamera.transform.localToWorldMatrix);
			}
		}

		/// <summary>
		/// Done with the camera, free up resources!
		/// </summary>
		public void Shutdown()
		{
			if (captureTex != null)
			{
				GameObject.Destroy(captureTex);
			}
		}
	}
}
