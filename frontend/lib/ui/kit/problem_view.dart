import 'package:flutter/material.dart';

import '../../api/api_client.dart';
import '../../api/error_text.dart';
import '../../theme/tokens.dart';

class _Problem {
  const _Problem(this.icon, this.title, this.message, this.tone, this.retryLabel);

  final IconData icon;
  final String title;
  final String message;
  final Tone tone;
  final String retryLabel;
}

_Problem _classify(Object error) {
  if (error is ApiException) {
    if (error.status == 403) {
      return const _Problem(
        Icons.lock_outline,
        'Không có quyền truy cập',
        'Bạn không có quyền thực hiện thao tác này. Vui lòng liên hệ quản trị viên nếu cần cấp quyền.',
        Tone.danger,
        'Thử lại',
      );
    }
    if (error.status == 404 && error.message.startsWith('No static resource')) {
      return const _Problem(
        Icons.update,
        'Máy chủ chưa có chức năng này',
        'Backend đang chạy có thể là bản cũ. Hãy khởi động lại backend rồi thử lại.',
        Tone.warn,
        'Thử lại',
      );
    }
    if (error.status == 404) {
      return const _Problem(
        Icons.search_off,
        'Không tìm thấy',
        'Không tìm thấy dữ liệu hoặc dữ liệu không thuộc phạm vi của bạn.',
        Tone.info,
        'Thử lại',
      );
    }
    if (error.status == 409 && error.code == 'VERSION_CONFLICT') {
      return const _Problem(
        Icons.sync_problem,
        'Dữ liệu đã thay đổi',
        'Dữ liệu vừa được người khác cập nhật. Vui lòng tải lại trước khi lưu.',
        Tone.warn,
        'Tải lại',
      );
    }
    if (error.status == 401) {
      return const _Problem(
        Icons.schedule,
        'Phiên đăng nhập đã hết hạn',
        'Phiên làm việc đã hết hạn. Vui lòng đăng nhập lại để tiếp tục.',
        Tone.warn,
        'Thử lại',
      );
    }
  }
  return _Problem(Icons.cloud_off_outlined, 'Không tải được dữ liệu', describeError(error), Tone.danger, 'Thử lại');
}

class ProblemView extends StatelessWidget {
  const ProblemView({super.key, required this.error, this.onRetry, this.onHome});

  final Object error;
  final VoidCallback? onRetry;
  final VoidCallback? onHome;

  @override
  Widget build(BuildContext context) {
    final problem = _classify(error);
    final tokens = AppTokens.of(context);
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 440),
        child: Padding(
          padding: const EdgeInsets.all(AppTokens.s5),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(color: tokens.toneBg[problem.tone], shape: BoxShape.circle),
                child: Icon(problem.icon, color: tokens.toneFg[problem.tone]),
              ),
              const SizedBox(height: AppTokens.s3),
              Text(problem.title, style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: AppTokens.s2),
              Text(problem.message),
              if (error is ApiException) ...[
                const SizedBox(height: AppTokens.s2),
                Text('Mã lỗi: ${(error as ApiException).code}', style: Theme.of(context).textTheme.bodySmall),
              ],
              const SizedBox(height: AppTokens.s4),
              Wrap(
                spacing: AppTokens.s2,
                runSpacing: AppTokens.s2,
                children: [
                  if (onRetry != null)
                    FilledButton.icon(onPressed: onRetry, icon: const Icon(Icons.refresh), label: Text(problem.retryLabel)),
                  if (onHome != null) OutlinedButton(onPressed: onHome, child: const Text('Về trang chính')),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
