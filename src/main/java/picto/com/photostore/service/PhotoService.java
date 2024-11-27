package picto.com.photostore.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import picto.com.photostore.domain.Photo;
import picto.com.photostore.domain.PhotoResponse;
import picto.com.photostore.domain.PhotoUploadRequest;
import picto.com.photostore.domain.User;
import picto.com.photostore.exception.*;
import picto.com.photostore.repository.PhotoRepository;
import picto.com.photostore.repository.UserRepository;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PhotoService {
    private final S3Service s3Service;
    private final PhotoRepository photoRepository;
    private final UserRepository userRepository;
    private final SessionSchedulerClient sessionSchedulerClient;

    // 사진 업로드
    public PhotoResponse uploadPhoto(MultipartFile file, PhotoUploadRequest request) {
        try {
            // 파일 유효성 검사
            validateFile(file);
            // 업로드 요청 사용자 정보 조회
            User user = userRepository.findById(request.getUserId())
                    .orElseThrow(() -> new UserNotFoundException("사용자를 찾을 수 없습니다."));

            // 프레임 true
            if (request.isFrameActive()) {
                // 임시 프레임 사진 생성
                return createTemporaryFramePhoto(request, user);
            }
            // 프레임 false 사진 업로드
            return createAndUploadPhoto(file, request, user);
        } catch (Exception e) {
            log.error("사진 업로드 실패", e);
            throw new PhotoUploadException("사진 업로드 중 오류가 발생했습니다.", e);
        }
    }

    // 임시 프레임 사진 생성
    private PhotoResponse createTemporaryFramePhoto(PhotoUploadRequest request, User user) {
        Photo photo = Photo.builder()
                .user(user)
                .photoPath("temp_path")
                .s3FileName("temp_file")
                .lat(0.0)
                .lng(0.0)
                .location("temp_location")
                .tag(request.getTag())
                .likes(0)
                .views(0)
                .frameActive(true)
                .sharedActive(false)
                .build();

        return PhotoResponse.from(photoRepository.save(photo));
    }

    // frame false 사진 업로드
    private PhotoResponse createAndUploadPhoto(MultipartFile file, PhotoUploadRequest request, User user) {
        try {
            String fileName = s3Service.uploadFile(file);
            String photoPath = s3Service.getFileUrl(fileName);

            Photo photo = Photo.builder()
                    .user(user)
                    .photoPath(photoPath)
                    .s3FileName(fileName)
                    .lat(request.getLat())
                    .lng(request.getLng())
                    .location(request.getLocation())
                    .tag(request.getTag())
                    .likes(0)
                    .views(0)
                    .frameActive(false)
                    .sharedActive(request.isSharedActive())
                    .build();

            Photo savedPhoto = photoRepository.save(photo);

            // Shared true인 경우
            if (request.isSharedActive()) {
                scheduleSession(savedPhoto);
            }

            return PhotoResponse.from(savedPhoto);
        } catch (Exception e) {
            throw new PhotoUploadException("사진 업로드 중 오류가 발생했습니다.", e);
        }
    }

    // 액자 사진 업로드
    @Transactional
    public PhotoResponse uploadFramePhoto(Long photoId, MultipartFile file, PhotoUploadRequest request) {
        // 사진 조회
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new PhotoNotFoundException("프레임 사진을 찾을 수 없습니다."));

        // 액자 사진인지 확인
        if (!photo.isFrameActive()) {
            throw new InvalidOperationException("프레임 사진이 아닙니다.");
        }

        // 파일 유효성 검사
        validateFile(file);

        try {
            String fileName = s3Service.uploadFile(file);
            String photoPath = s3Service.getFileUrl(fileName);

            // 기존 사진 정보 업데이트
            photo.updatePhoto(
                    request.getLat(),
                    request.getLng(),
                    request.getLocation(),
                    request.getTag(),
                    photoPath,
                    fileName,
                    false,
                    request.isSharedActive()
            );

            Photo updatedPhoto = photoRepository.save(photo);

            // Shared true인 경우
            if (request.isSharedActive()) {
                try {
                    sessionSchedulerClient.scheduleSession(
                            updatedPhoto.getPhotoId(),
                            updatedPhoto.getUser().getUserId(),
                            updatedPhoto.getLng(),
                            updatedPhoto.getLat()
                    );
                } catch (Exception e) {
                    log.warn("Session scheduler is not available: {}", e.getMessage());
                }
            }

            return PhotoResponse.from(updatedPhoto);
        } catch (Exception e) {
            log.error("프레임 사진 업로드 실패", e);
            throw new PhotoUploadException("프레임 사진 업로드 중 문제가 발생했습니다.", e);
        }
    }

    // 사진 공유 상태 업데이트
    @Transactional
    public PhotoResponse updateShareStatus(Long photoId, boolean shared) {
        // 사진 조회
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new PhotoNotFoundException("사진을 찾을 수 없습니다."));

        // 액자 사진인 경우
        if (photo.isFrameActive()) {
            throw new InvalidOperationException("프레임 상태의 사진은 공유 상태를 변경할 수 없습니다.");
        }

        photo.updatePhoto(
                photo.getLat(),
                photo.getLng(),
                photo.getLocation(),
                photo.getTag(),
                photo.getPhotoPath(),
                photo.getS3FileName(),
                photo.isFrameActive(),
                shared
        );

        // Shared true인 경우
        if (shared) {
            try {
                sessionSchedulerClient.scheduleSession(
                        photo.getPhotoId(),
                        photo.getUser().getUserId(),
                        photo.getLng(),
                        photo.getLat()
                );
            } catch (Exception e) {
                log.error("공유 상태 변경 중 세션 스케줄러 호출 실패", e);
                throw new SessionSchedulerException("세션 스케줄링 실패", e);
            }
        }

        return PhotoResponse.from(photoRepository.save(photo));
    }

    // 사진 삭제
    public void deletePhoto(Long photoId, Long userId) {
        // 사진 조회
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new PhotoNotFoundException("사진을 찾을 수 없습니다."));

        // 요청 사용자가 사진 소유자인지 확인
        if (!photo.getUser().getUserId().equals(userId)) {
            throw new UnauthorizedException("해당 사진을 삭제할 권한이 없습니다.");
        }

        try {
            s3Service.deleteFile(photo.getS3FileName());
            photoRepository.delete(photo);
        } catch (Exception e) {
            log.error("사진 삭제 중 오류 발생", e);
            throw new FileDeleteException("사진 삭제 중 오류가 발생했습니다.", e);
        }
    }

    private void scheduleSession(Photo photo) {
        try {
            sessionSchedulerClient.scheduleSession(
                    photo.getPhotoId(),
                    photo.getUser().getUserId(),
                    photo.getLng(),
                    photo.getLat()
            );
        } catch (Exception e) {
            log.error("Session scheduler call failed", e);
            throw new SessionSchedulerException("세션 스케줄링 실패", e);
        }
    }

    // 사진 ID 사진 정보 조회
    public Photo getPhotoById(Long photoId) {
        return photoRepository.findById(photoId)
                .orElseThrow(() -> new PhotoNotFoundException("사진을 찾을 수 없습니다. ID: " + photoId));
    }

    // 파일 유효성 검사
    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidFileException("파일이 비어있습니다.");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new InvalidFileException("이미지 파일만 업로드 가능합니다.");
        }

        if (file.getSize() > 10 * 1024 * 1024) {
            throw new InvalidFileException("파일 크기는 10MB를 초과할 수 없습니다.");
        }
    }
}