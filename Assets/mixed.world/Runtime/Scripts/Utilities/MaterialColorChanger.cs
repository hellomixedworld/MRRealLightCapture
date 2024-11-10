using UnityEngine;

public class MaterialColorChanger : MonoBehaviour
{
    [SerializeField] private Material targetMaterial;
    

    private void Start()
    {
        if (targetMaterial == null){
            Renderer renderer = GetComponent<Renderer>();
            if (renderer != null) {
                targetMaterial = renderer.sharedMaterial;
            } else {
                MeshRenderer meshRenderer = GetComponent<MeshRenderer>();
                if (meshRenderer != null) {
                    targetMaterial = meshRenderer.sharedMaterial;
                }
            }
        }
    }
    [ContextMenu("Change Color")]
    public void ChangeColor()
    {
        if (targetMaterial == null)
        {
            Debug.LogError("Target material is not assigned!");
            return;
        }

        Color randomColor = new Color(
            Random.value,  // Random red value between 0 and 1
            Random.value,  // Random green value between 0 and 1
            Random.value,  // Random blue value between 0 and 1
            1f            // Full opacity
        );

        targetMaterial.SetColor("_BaseColor", randomColor);  // URP uses _BaseColor instead of _Color
    }
}