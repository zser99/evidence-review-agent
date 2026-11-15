// CI/CD 파이프라인 (아키텍처 정의서 3장: Git → Jenkins → BuildKit → Harbor → Helm 값 갱신 → Argo CD → EKS)
//
//  1. test      백엔드(JUnit) · AI 서버(pytest) · 프런트엔드(빌드) 검증
//  2. build     BuildKit(rootless)으로 이미지 3개를 빌드해 Harbor에 push
//  3. manifest  Helm 값(values-eks.yaml)의 image.tag 를 새 태그로 바꿔 Git에 커밋
//  4. deploy    Argo CD가 Git 변경을 감지해 EKS에 반영(자동 동기화) — Jenkins는 클러스터 자격 증명을 갖지 않는다
//
// 필요한 Jenkins 설정
//  - Kubernetes 플러그인(에이전트 파드), Credentials:
//      harbor-credentials  (Username/Password)  Harbor 로봇 계정
//      git-credentials     (Username/Password)  매니페스트 커밋용 토큰
//  - 환경변수(폴더/잡 수준): HARBOR_HOST(예: harbor.example.com), HARBOR_PROJECT(예: evidence)
pipeline {
  agent {
    kubernetes {
      defaultContainer 'jnlp'
      yaml '''
apiVersion: v1
kind: Pod
spec:
  securityContext:
    fsGroup: 1000
  containers:
    - name: maven
      image: maven:3.9-eclipse-temurin-21
      command: ["sleep", "infinity"]
      resources: {requests: {cpu: "1", memory: 2Gi}, limits: {memory: 3Gi}}
    - name: python
      image: python:3.11-slim
      command: ["sleep", "infinity"]
      resources: {requests: {cpu: 500m, memory: 1Gi}, limits: {memory: 2Gi}}
    - name: node
      image: node:22-alpine
      command: ["sleep", "infinity"]
      resources: {requests: {cpu: 500m, memory: 512Mi}, limits: {memory: 1Gi}}
    - name: buildkit
      image: moby/buildkit:rootless
      command: ["sleep", "infinity"]
      env:
        - {name: BUILDKITD_FLAGS, value: "--oci-worker-no-process-sandbox"}
      securityContext:
        runAsUser: 1000
        runAsGroup: 1000
        seccompProfile: {type: Unconfined}
        appArmorProfile: {type: Unconfined}
      resources: {requests: {cpu: "1", memory: 2Gi}, limits: {memory: 4Gi}}
'''
    }
  }

  options {
    timeout(time: 45, unit: 'MINUTES')
    disableConcurrentBuilds()
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }

  environment {
    REGISTRY = "${env.HARBOR_HOST}/${env.HARBOR_PROJECT ?: 'evidence'}"
    VALUES_FILE = 'deploy/helm/evidence-agent/values-eks.yaml'
  }

  stages {
    stage('checkout') {
      steps {
        checkout scm
        script {
          env.GIT_SHA = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()
          env.IMAGE_TAG = "${env.BUILD_NUMBER}-${env.GIT_SHA}"
          // 파이프라인이 만든 매니페스트 커밋으로 다시 빌드되지 않게 한다
          env.SKIP = sh(script: "git log -1 --pretty=%B | grep -q '\\[skip ci\\]' && echo true || echo false", returnStdout: true).trim()
        }
        echo "image tag: ${env.IMAGE_TAG} (skip=${env.SKIP})"
      }
    }

    stage('test') {
      when { expression { env.SKIP != 'true' } }
      parallel {
        stage('backend') {
          steps {
            container('maven') {
              sh 'mvn -B -q -f backend/pom.xml test'
            }
          }
          post { always { junit allowEmptyResults: true, testResults: 'backend/target/surefire-reports/*.xml' } }
        }
        stage('ai-server') {
          steps {
            container('python') {
              sh '''
                apt-get update -qq && apt-get install -y -qq --no-install-recommends libgl1 libglib2.0-0 libgomp1 fonts-nanum >/dev/null
                pip install -q -r ai-server/requirements-dev.txt
                cd ai-server && OCR_ENGINE=none python -m pytest -q --junitxml=pytest.xml
              '''
            }
          }
          post { always { junit allowEmptyResults: true, testResults: 'ai-server/pytest.xml' } }
        }
        stage('frontend') {
          steps {
            container('node') {
              sh 'cd frontend && npm ci --no-audit --no-fund && npm run build'
            }
          }
        }
      }
    }

    stage('build & push') {
      when { allOf { branch 'main'; expression { env.SKIP != 'true' } } }
      steps {
        container('buildkit') {
          withCredentials([usernamePassword(credentialsId: 'harbor-credentials', usernameVariable: 'HARBOR_USER', passwordVariable: 'HARBOR_PASS')]) {
            sh '''
              set -eu
              mkdir -p ~/.docker
              AUTH=$(printf '%s:%s' "$HARBOR_USER" "$HARBOR_PASS" | base64 | tr -d '\\n')
              printf '{"auths":{"%s":{"auth":"%s"}}}' "$HARBOR_HOST" "$AUTH" > ~/.docker/config.json

              build() {  # name, context, dockerfile-dir
                buildctl-daemonless.sh build --frontend dockerfile.v0 \
                  --local context="$2" --local dockerfile="$3" \
                  --export-cache type=registry,ref=$REGISTRY/$1:buildcache,mode=max \
                  --import-cache type=registry,ref=$REGISTRY/$1:buildcache \
                  --output type=image,\\"name=$REGISTRY/$1:$IMAGE_TAG,$REGISTRY/$1:latest\\",push=true
              }
              build backend   .         backend
              build ai-server ai-server ai-server
              build frontend  frontend  frontend
            '''
          }
        }
      }
    }

    stage('update manifest') {
      when { allOf { branch 'main'; expression { env.SKIP != 'true' } } }
      steps {
        withCredentials([usernamePassword(credentialsId: 'git-credentials', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
          sh '''
            set -eu
            # image: 블록 안의 tag 값만 바꾼다
            sed -i -E "/^image:/,/^[a-zA-Z]/ s|^(  tag: ).*|\\1${IMAGE_TAG}|" "$VALUES_FILE"
            grep -n "tag:" "$VALUES_FILE"
            git config user.name  "jenkins"
            git config user.email "jenkins@ci.local"
            git add "$VALUES_FILE"
            git commit -m "deploy: evidence-agent ${IMAGE_TAG} [skip ci]"
            REMOTE=$(git config --get remote.origin.url | sed -E "s|https://|https://${GIT_USER}:${GIT_TOKEN}@|")
            git push "$REMOTE" HEAD:main
          '''
        }
      }
    }
  }

  post {
    success { echo "완료: ${env.IMAGE_TAG} — Argo CD가 values-eks.yaml 변경을 감지해 EKS에 배포합니다." }
    failure { echo '실패: 위 단계의 로그를 확인하세요. 배포는 진행되지 않았습니다.' }
  }
}
