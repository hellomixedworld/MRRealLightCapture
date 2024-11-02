using UnityEngine;

namespace mixed.world.Utilities
{
    public class TransformFollower : MonoBehaviour
    {
        [SerializeField] private Transform target;
        [SerializeField] private bool followPosition = true;
        [SerializeField] private bool followRotation = true;
        [SerializeField] private bool followScale = false;
        
        [SerializeField] private Vector3 positionOffset;
        [SerializeField] private Vector3 rotationOffset;
        [SerializeField] private Vector3 scaleOffset = Vector3.one;
        
        [SerializeField] private bool smoothFollow;
        [SerializeField] private float smoothSpeed = 10f;
        
        private void LateUpdate()
        {
            if (target == null) return;

            if (smoothFollow)
            {
                if (followPosition)
                {
                    transform.position = Vector3.Lerp(transform.position, 
                        target.position + positionOffset, 
                        Time.deltaTime * smoothSpeed);
                }

                if (followRotation)
                {
                    transform.rotation = Quaternion.Lerp(transform.rotation, 
                        target.rotation * Quaternion.Euler(rotationOffset), 
                        Time.deltaTime * smoothSpeed);
                }

                if (followScale)
                {
                    transform.localScale = Vector3.Lerp(transform.localScale, 
                        Vector3.Scale(target.localScale, scaleOffset), 
                        Time.deltaTime * smoothSpeed);
                }
            }
            else
            {
                if (followPosition)
                {
                    transform.position = target.position + positionOffset;
                }

                if (followRotation)
                {
                    transform.rotation = target.rotation * Quaternion.Euler(rotationOffset);
                }

                if (followScale)
                {
                    transform.localScale = Vector3.Scale(target.localScale, scaleOffset);
                }
            }
        }

        public void SetTarget(Transform newTarget)
        {
            target = newTarget;
        }

        public void ClearTarget()
        {
            target = null;
        }
    }
} 